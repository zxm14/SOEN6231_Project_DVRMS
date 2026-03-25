package server;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replica Manager — one per replica.
 * Usage: java server.ReplicaManager <replicaId>
 */
public class ReplicaManager {

    private final int replicaId;
    private final int rmPort;
    private final int replicaPort;
    private Process replicaProcess;
    private final ReliableUDPSender sender = new ReliableUDPSender();

    // Vote collection: voteKey → (rmId → vote)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> voteCollector =
        new ConcurrentHashMap<>();

    private static final int HEARTBEAT_INTERVAL_MS = 3000;
    private static final int HEARTBEAT_TIMEOUT_MS = 2000;
    private static final int VOTE_TIMEOUT_MS = 5000;

    public ReplicaManager(int replicaId) {
        this.replicaId = replicaId;
        this.rmPort = PortConfig.ALL_RMS[replicaId - 1];
        this.replicaPort = PortConfig.ALL_REPLICAS[replicaId - 1];
    }

    public void start() {
        launchReplica();
        new Thread(this::heartbeatLoop, "RM" + replicaId + "-Heartbeat").start();
        listenForMessages();
    }

    private void launchReplica() {
        try {
            replicaProcess = new ProcessBuilder(
                "java", "-cp", System.getProperty("java.class.path"),
                "server.ReplicaLauncher", String.valueOf(replicaId)
            ).inheritIO().start();
            System.out.println("RM" + replicaId + ": Replica launched on port " + replicaPort);
        } catch (Exception e) {
            System.err.println("RM" + replicaId + ": Failed to launch replica: " + e.getMessage());
        }
    }

    private void killReplica() {
        if (replicaProcess != null && replicaProcess.isAlive()) {
            replicaProcess.destroyForcibly();
            System.out.println("RM" + replicaId + ": Replica killed");
        }
    }

    private void heartbeatLoop() {
        while (true) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
                boolean alive = sendHeartbeat();
                if (!alive) {
                    System.out.println("RM" + replicaId + ": Heartbeat FAILED");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean sendHeartbeat() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(HEARTBEAT_TIMEOUT_MS);
            String msg = "HEARTBEAT_CHECK:" + replicaId;
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length,
                InetAddress.getByName("localhost"), replicaPort));

            byte[] buf = new byte[8192];
            DatagramPacket reply = new DatagramPacket(buf, buf.length);
            socket.receive(reply);
            String response = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8);
            return response.startsWith("HEARTBEAT_ACK");
        } catch (Exception e) {
            return false;
        }
    }

    private void listenForMessages() {
        try (DatagramSocket socket = new DatagramSocket(rmPort)) {
            byte[] buf = new byte[8192];
            System.out.println("RM" + replicaId + " listening on port " + rmPort);

            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String raw = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                UDPMessage msg = UDPMessage.parse(raw);

                switch (msg.getType()) {
                    case REPLACE_REQUEST:
                        handleByzantineReplace(msg, socket);
                        break;
                    case CRASH_SUSPECT:
                        handleCrashSuspect(msg, socket);
                        break;
                    case VOTE_BYZANTINE:
                    case VOTE_CRASH:
                        handleVote(msg, socket);
                        break;
                    case STATE_REQUEST:
                        handleStateRequest(msg, socket, packet);
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            System.err.println("RM" + replicaId + " error: " + e.getMessage());
        }
    }

    private void handleByzantineReplace(UDPMessage msg, DatagramSocket socket) {
        String faultyReplicaId = msg.getField(0);
        System.out.println("RM" + replicaId + ": Byzantine replace requested for " + faultyReplicaId);

        for (int port : PortConfig.ALL_RMS) {
            try {
                sender.send("VOTE_BYZANTINE:" + faultyReplicaId,
                    InetAddress.getByName("localhost"), port, socket);
            } catch (Exception e) {
                System.err.println("RM" + replicaId + ": vote send error: " + e.getMessage());
            }
        }
    }

    private void handleCrashSuspect(UDPMessage msg, DatagramSocket socket) {
        String suspectedId = msg.getField(2); // CRASH_SUSPECT:reqID:seqNum:suspectedID
        boolean alive = sendHeartbeat();
        String vote = alive
            ? "VOTE_CRASH:" + suspectedId + ":ALIVE"
            : "VOTE_CRASH:" + suspectedId + ":CRASH_CONFIRMED";
        for (int rmPort : PortConfig.ALL_RMS) {
            try {
                sender.send(vote,
                    InetAddress.getByName("localhost"), rmPort, socket);
            } catch (Exception e) {
                System.err.println("RM" + replicaId + ": vote send error: " + e.getMessage());
            }
        }
    }

    private void handleVote(UDPMessage msg, DatagramSocket socket) {
        // VOTE_BYZANTINE:faultyReplicaID or VOTE_CRASH:suspectedID:verdict
        String voteType = msg.getType().name();
        String targetId = msg.getField(0);
        String voteKey = voteType + ":" + targetId;
        String voterDecision = msg.fieldCount() > 1 ? msg.getField(1) : "AGREE";

        // Record this vote
        voteCollector.computeIfAbsent(voteKey, k -> new ConcurrentHashMap<>())
            .put("RM" + replicaId, voterDecision);

        // Check majority
        ConcurrentHashMap<String, String> votes = voteCollector.get(voteKey);
        if (votes != null) {
            int totalVotes = votes.size();
            if (totalVotes < 2) return;

            long agreeCount = votes.values().stream()
                .filter(v -> v.equals("AGREE") || v.equals("CRASH_CONFIRMED")).count();
            if (agreeCount > totalVotes / 2) {
                replaceReplica();
                voteCollector.remove(voteKey);
            }
        }
    }

    private void handleStateRequest(UDPMessage msg, DatagramSocket socket, DatagramPacket from) {
        try (DatagramSocket reqSocket = new DatagramSocket()) {
            String stateReq = "STATE_REQUEST:" + replicaId;
            byte[] data = stateReq.getBytes(StandardCharsets.UTF_8);
            reqSocket.send(new DatagramPacket(data, data.length,
                InetAddress.getByName("localhost"), replicaPort));

            reqSocket.setSoTimeout(10000);
            byte[] buf = new byte[65535];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            reqSocket.receive(response);
            String stateResponse = new String(response.getData(), 0, response.getLength(),
                StandardCharsets.UTF_8);

            byte[] forwardData = stateResponse.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(forwardData, forwardData.length,
                from.getAddress(), from.getPort()));
        } catch (Exception e) {
            System.err.println("RM" + replicaId + ": State request handling failed: " + e.getMessage());
        }
    }

    private void replaceReplica() {
        System.out.println("RM" + replicaId + ": Starting replica replacement");

        killReplica();
        launchReplica();

        String snapshot = requestStateFromHealthyReplica();
        int lastSeqNum = -1;

        if (snapshot != null) {
            try (DatagramSocket socket = new DatagramSocket()) {
                String initMsg = "INIT_STATE:" + snapshot;
                byte[] data = initMsg.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(data, data.length,
                    InetAddress.getByName("localhost"), replicaPort));

                socket.setSoTimeout(5000);
                byte[] buf = new byte[8192];
                DatagramPacket ack = new DatagramPacket(buf, buf.length);
                socket.receive(ack);
                String ackStr = new String(ack.getData(), 0, ack.getLength(),
                    StandardCharsets.UTF_8);
                String[] ackParts = ackStr.split(":");
                if (ackParts.length >= 4) {
                    lastSeqNum = Integer.parseInt(ackParts[3]);
                }
                System.out.println("RM" + replicaId + ": State transfer complete, lastSeq=" + lastSeqNum);
            } catch (Exception e) {
                System.err.println("RM" + replicaId + ": State transfer failed: " + e.getMessage());
            }
        }

        try (DatagramSocket socket = new DatagramSocket()) {
            String readyMsg = "REPLICA_READY:" + replicaId + ":localhost:" + replicaPort
                + ":" + lastSeqNum;
            sender.send(readyMsg, InetAddress.getByName("localhost"),
                PortConfig.SEQUENCER, socket);
            sender.send(readyMsg, InetAddress.getByName("localhost"),
                PortConfig.FE_UDP, socket);
            for (int rmPort : PortConfig.ALL_RMS) {
                sender.send(readyMsg, InetAddress.getByName("localhost"), rmPort, socket);
            }
        } catch (Exception e) {
            System.err.println("RM" + replicaId + ": REPLICA_READY send failed: " + e.getMessage());
        }
    }

    private String requestStateFromHealthyReplica() {
        for (int i = 0; i < PortConfig.ALL_RMS.length; i++) {
            int targetRmPort = PortConfig.ALL_RMS[i];
            if (targetRmPort == rmPort) continue;

            try (DatagramSocket socket = new DatagramSocket()) {
                String req = "STATE_REQUEST:" + replicaId;
                sender.send(req, InetAddress.getByName("localhost"), targetRmPort, socket);

                socket.setSoTimeout(10000);
                byte[] buf = new byte[65535];
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                socket.receive(response);
                String raw = new String(response.getData(), 0, response.getLength(),
                    StandardCharsets.UTF_8);

                if (raw.startsWith("STATE_TRANSFER:")) {
                    UDPMessage stateMsg = UDPMessage.parse(raw);
                    return stateMsg.getField(1);
                }
            } catch (Exception e) {
                System.err.println("RM" + replicaId + ": State request to RM port "
                    + targetRmPort + " failed, trying next");
            }
        }
        System.err.println("RM" + replicaId + ": No healthy RM responded with state");
        return null;
    }

    public static void main(String[] args) {
        int id = Integer.parseInt(args[0]);
        new ReplicaManager(id).start();
    }
}
