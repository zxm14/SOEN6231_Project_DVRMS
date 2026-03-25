package server;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Starts one replica (3 offices: MTL, WPG, BNF) with UDP listener.
 * No SOAP endpoint — all requests come through the Sequencer.
 *
 * Usage: java server.ReplicaLauncher <replicaId>
 *        where replicaId = 1, 2, 3, or 4
 */
public class ReplicaLauncher {

    public static void main(String[] args) {
        int replicaId = Integer.parseInt(args[0]);
        int replicaPort = PortConfig.ALL_REPLICAS[replicaId - 1];
        String replicaIdToken = String.valueOf(replicaId);

        // Create the 3 office instances (same as A3, but no SOAP publish)
        VehicleReservationWS mtl =
            new VehicleReservationWS("MTL", PortConfig.officePort(replicaId, "MTL"), replicaIdToken);
        VehicleReservationWS wpg =
            new VehicleReservationWS("WPG", PortConfig.officePort(replicaId, "WPG"), replicaIdToken);
        VehicleReservationWS bnf =
            new VehicleReservationWS("BNF", PortConfig.officePort(replicaId, "BNF"), replicaIdToken);

        Map<String, VehicleReservationWS> offices = new HashMap<>();
        offices.put("MTL", mtl);
        offices.put("WPG", wpg);
        offices.put("BNF", bnf);

        System.out.println("Replica " + replicaId + " started on UDP port " + replicaPort);

        try (DatagramSocket socket = new DatagramSocket(replicaPort)) {
            byte[] buf = new byte[8192];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String raw = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                UDPMessage msg = UDPMessage.parse(raw);
                switch (msg.getType()) {
                    case EXECUTE: {
                        int seqNum = Integer.parseInt(msg.getField(0));
                        String reqID = msg.getField(1);
                        String feHost = msg.getField(2);
                        int fePort = Integer.parseInt(msg.getField(3));
                        // Remaining fields = "op:params"
                        StringBuilder op = new StringBuilder(msg.getField(4));
                        for (int i = 5; i < msg.fieldCount(); i++) {
                            op.append(':').append(msg.getField(i));
                        }

                        String officeId = extractTargetOffice(op.toString());
                        VehicleReservationWS target = offices.getOrDefault(officeId, mtl);
                        String result = target.handleExecute(seqNum, reqID, feHost, fePort, op.toString());

                        // Send ACK (or NACK if a sequence gap was detected) back to Sequencer
                        String reply = (result != null && result.startsWith("NACK:")) ? result : "ACK:" + seqNum;
                        byte[] ackData = reply.getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(ackData, ackData.length,
                            packet.getAddress(), packet.getPort()));
                        break;
                    }
                    case HEARTBEAT_CHECK: {
                        String reply = "HEARTBEAT_ACK:" + replicaId + ":" + mtl.getNextExpectedSeq();
                        byte[] replyData = reply.getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(replyData, replyData.length,
                            packet.getAddress(), packet.getPort()));
                        break;
                    }
                    case SET_BYZANTINE: {
                        boolean enable = msg.fieldCount() > 0 && "true".equalsIgnoreCase(msg.getField(0));
                        for (VehicleReservationWS office : offices.values()) {
                            office.handleUDPRequest("SET_BYZANTINE:" + enable);
                        }
                        String reply = "ACK:SET_BYZANTINE:" + enable;
                        byte[] replyData = reply.getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(replyData, replyData.length,
                            packet.getAddress(), packet.getPort()));
                        break;
                    }
                    case STATE_REQUEST: {
                        // Collect snapshot from all 3 offices
                        StringBuilder snapshot = new StringBuilder();
                        snapshot.append(mtl.getStateSnapshot()).append("|");
                        snapshot.append(wpg.getStateSnapshot()).append("|");
                        snapshot.append(bnf.getStateSnapshot());
                        String reply = "STATE_TRANSFER:" + replicaId + ":" + snapshot.toString();
                        byte[] replyData = reply.getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(replyData, replyData.length,
                            packet.getAddress(), packet.getPort()));
                        break;
                    }
                    case INIT_STATE: {
                        // Load snapshot into all 3 offices
                        // Format: INIT_STATE:mtlSnapshot|wpgSnapshot|bnfSnapshot
                        String[] snapshots = msg.getField(0).split("\\|");
                        if (snapshots.length < 3) {
                            System.err.println("Replica " + replicaId + ": malformed INIT_STATE, ignoring");
                            break;
                        }
                        mtl.loadStateSnapshot(snapshots[0]);
                        wpg.loadStateSnapshot(snapshots[1]);
                        bnf.loadStateSnapshot(snapshots[2]);
                        int lastSeqNum = mtl.getNextExpectedSeq() - 1;
                        String reply = "ACK:INIT_STATE:" + replicaId + ":" + lastSeqNum;
                        byte[] replyData = reply.getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(replyData, replyData.length,
                            packet.getAddress(), packet.getPort()));
                        break;
                    }
                    case ACK:
                        break; // silently ignore
                    default:
                        System.out.println("Replica " + replicaId + ": unhandled message type " + msg.getType());
                        break;
                }
            }
        } catch (Exception e) {
            System.err.println("Replica " + replicaId + " error: " + e.getMessage());
        }
    }

    private static String extractTargetOffice(String operation) {
        String[] parts = operation.split(":", -1);
        String op = parts[0];
        switch (op) {
            case "FIND":
                return null; // broadcast — caller must query all 3
            case "LISTRES":
                return ServerIdRules.extractOfficeID(parts[1]);
            default:
                // RESERVE, CANCEL, WAITLIST, ATOMIC_UPDATE — vehicleID at parts[2]
                if (parts.length >= 3) {
                    return ServerIdRules.extractOfficeID(parts[2]);
                }
                return "MTL";
        }
    }
}
