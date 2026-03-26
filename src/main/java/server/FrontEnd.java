package server;

import java.util.Map;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
import javax.xml.ws.Endpoint;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@WebService(serviceName = "VehicleReservationService")
public class FrontEnd {

    private final ConcurrentHashMap<String, RequestContext> pendingRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> byzantineCount = new ConcurrentHashMap<>();
    private final AtomicLong slowestResponseTime = new AtomicLong(2000);
    private final ReliableUDPSender sender;
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    static class RequestContext {
        final String requestID;
        final long sentTime = System.currentTimeMillis();
        final ConcurrentHashMap<String, String> replicaResults = new ConcurrentHashMap<>();
        private final CompletableFuture<String> majorityFuture = new CompletableFuture<>();
        volatile int seqNum = -1;

        RequestContext(String requestID) { this.requestID = requestID; }

        void addResult(String replicaID, String result, int seqNum) {
            if (this.seqNum < 0) this.seqNum = seqNum;
            replicaResults.put(replicaID, result);
            long matchCount = replicaResults.values().stream()
                .filter(v -> v.equals(result)).count();
            if (matchCount >= 2) {
                majorityFuture.complete(result);
            }
        }

        String awaitMajority(long timeoutMs) {
            try {
                return majorityFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return null;
            }
        }
    }

    public FrontEnd() {
        this(new ReliableUDPSender());
    }

    public FrontEnd(ReliableUDPSender sender) {
        this.sender = sender;
        if (!"true".equals(System.getProperty("dvrms.disable.udp"))) {
            new Thread(this::listenForResults, "FE-ResultListener").start();
        }
    }

    // ===== @WebMethod — same signatures as VehicleReservationWS =====

    @WebMethod
    public String addVehicle(
            @WebParam(name = "managerID") String managerID,
            @WebParam(name = "vehicleNumber") String vehicleNumber,
            @WebParam(name = "vehicleType") String vehicleType,
            @WebParam(name = "vehicleID") String vehicleID,
            @WebParam(name = "reservationPrice") double reservationPrice) {
        String operation = "ADDVEHICLE:" + managerID + ":" + vehicleNumber + ":" + vehicleType + ":" + vehicleID + ":" + reservationPrice;
        return forwardAndCollect(operation);
    }

    @WebMethod
    public String removeVehicle(
            @WebParam(name = "managerID") String managerID,
            @WebParam(name = "vehicleID") String vehicleID) {
        String operation = "REMOVEVEHICLE:" + managerID + ":" + vehicleID;
        return forwardAndCollect(operation);
    }

    @WebMethod
    public String listAvailableVehicle(@WebParam(name = "managerID") String managerID) {
        String operation = "LISTAVAILABLE:" + managerID;
        return forwardAndCollect(operation);
    }

    @WebMethod
    public String reserveVehicle(
            @WebParam(name = "customerID") String customerID,
            @WebParam(name = "vehicleID") String vehicleID,
            @WebParam(name = "startDate") String startDate,
            @WebParam(name = "endDate") String endDate) {
        // Execute-path operation: route by customer office so A3 home-office logic is preserved.
        String operation = "RESERVE_EXECUTE:" + customerID + ":" + vehicleID + ":" + startDate + ":" + endDate;
        return forwardAndCollect(operation);
    }

    @WebMethod
    public String updateReservation(
            @WebParam(name = "customerID") String customerID,
            @WebParam(name = "vehicleID") String vehicleID,
            @WebParam(name = "newStartDate") String newStartDate,
            @WebParam(name = "newEndDate") String newEndDate) {
        // Execute-path operation: route by customer office so A3 home-office logic is preserved.
        String operation =
            "ATOMIC_UPDATE_EXECUTE:" + customerID + ":" + vehicleID + ":" + newStartDate + ":" + newEndDate;
        return forwardAndCollect(operation);
    }

    @WebMethod
    public String cancelReservation(
            @WebParam(name = "customerID") String customerID,
            @WebParam(name = "vehicleID") String vehicleID) {
        // Execute-path operation: route by customer office so A3 home-office logic is preserved.
        String operation = "CANCEL_EXECUTE:" + customerID + ":" + vehicleID;
        return forwardAndCollect(operation);
    }

    @WebMethod
    public String findVehicle(
            @WebParam(name = "customerID") String customerID,
            @WebParam(name = "vehicleType") String vehicleType) {
        String operation = "FIND:" + customerID + ":" + vehicleType;
        return forwardAndCollect(operation);
    }

    @WebMethod
    public String listCustomerReservations(@WebParam(name = "customerID") String customerID) {
        String operation = "LISTRES:" + customerID;
        return forwardAndCollect(operation);
    }

    @WebMethod
    public String addToWaitList(
            @WebParam(name = "customerID") String customerID,
            @WebParam(name = "vehicleID") String vehicleID,
            @WebParam(name = "startDate") String startDate,
            @WebParam(name = "endDate") String endDate) {
        String operation = "WAITLIST:" + customerID + ":" + vehicleID + ":" + startDate + ":" + endDate;
        return forwardAndCollect(operation);
    }

    // ===== Core: forward to Sequencer and collect results =====

    private String forwardAndCollect(String operation) {
        String reqID = "REQ-" + requestCounter.incrementAndGet();
        RequestContext ctx = new RequestContext(reqID);
        pendingRequests.put(reqID, ctx);

        String requestMsg = "REQUEST:" + reqID + ":localhost:" + PortConfig.FE_UDP + ":" + operation;
        try {
            DatagramSocket socket = new DatagramSocket();
            sender.send(requestMsg, InetAddress.getByName("localhost"), PortConfig.SEQUENCER, socket);
            socket.close();
        } catch (Exception e) {
            pendingRequests.remove(reqID);
            return "FAIL: Could not reach Sequencer";
        }

        long timeout = 2 * slowestResponseTime.get();
        String majorityResult = ctx.awaitMajority(timeout);

        if (majorityResult != null) {
            processResults(ctx, majorityResult);
            return majorityResult;
        }

        return vote(ctx);
    }

    private String vote(RequestContext ctx) {
        ConcurrentHashMap<String, java.util.List<String>> resultToReplicas = new ConcurrentHashMap<>();
        for (Map.Entry<String, String> entry : ctx.replicaResults.entrySet()) {
            resultToReplicas.computeIfAbsent(entry.getValue(), k -> new CopyOnWriteArrayList<>())
                .add(entry.getKey());
        }

        String majorityResult = null;
        for (Map.Entry<String, java.util.List<String>> entry : resultToReplicas.entrySet()) {
            if (entry.getValue().size() >= 2) {
                majorityResult = entry.getKey();
                break;
            }
        }

        if (majorityResult == null) {
            pendingRequests.remove(ctx.requestID);
            return "FAIL: No majority result";
        }

        processResults(ctx, majorityResult);
        return majorityResult;
    }

    private void processResults(RequestContext ctx, String majorityResult) {
        for (Map.Entry<String, String> entry : ctx.replicaResults.entrySet()) {
            String replicaID = entry.getKey();
            if (entry.getValue().equals(majorityResult)) {
                byzantineCount.computeIfAbsent(replicaID, k -> new AtomicInteger(0)).set(0);
            } else {
                int count = byzantineCount.computeIfAbsent(replicaID, k -> new AtomicInteger(0))
                    .incrementAndGet();
                sendToAllRMs("INCORRECT_RESULT:" + ctx.requestID + ":" + ctx.seqNum + ":" + replicaID);
                if (count >= 3) {
                    sendToAllRMs("REPLACE_REQUEST:" + replicaID + ":BYZANTINE_THRESHOLD");
                }
            }
        }
        // Report crash for non-responding replicas
        for (int i = 0; i < PortConfig.ALL_REPLICAS.length; i++) {
            String rid = String.valueOf(i + 1);
            if (!ctx.replicaResults.containsKey(rid)) {
                sendToAllRMs("CRASH_SUSPECT:" + ctx.requestID + ":" + ctx.seqNum + ":" + rid);
            }
        }
        long elapsed = System.currentTimeMillis() - ctx.sentTime;
        slowestResponseTime.updateAndGet(prev -> Math.max(prev, elapsed));
        pendingRequests.remove(ctx.requestID);
    }

    // ===== UDP listener for RESULT messages =====

    private void ackUdpMessage(DatagramSocket socket, DatagramPacket packet, String token) {
        try {
            String ack = "ACK:" + token;
            byte[] ackData = ack.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(ackData, ackData.length, packet.getAddress(), packet.getPort()));
        } catch (Exception e) {
            System.err.println("FE ACK send error: " + e.getMessage());
        }
    }

    private void listenForResults() {
        try (DatagramSocket socket = new DatagramSocket(PortConfig.FE_UDP)) {
            byte[] buf = new byte[8192];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String raw = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                UDPMessage msg = UDPMessage.parse(raw);
                if (msg.getType() == UDPMessage.Type.RESULT) {
                    ackUdpMessage(socket, packet, "RESULT");
                    int seqNum = Integer.parseInt(msg.getField(0));
                    String reqID = msg.getField(1);
                    String replicaID = msg.getField(2);
                    // Result is everything from field 3 onward
                    StringBuilder result = new StringBuilder(msg.getField(3));
                    for (int i = 4; i < msg.fieldCount(); i++) {
                        result.append(':').append(msg.getField(i));
                    }

                    RequestContext ctx = pendingRequests.get(reqID);
                    if (ctx != null) {
                        ctx.addResult(replicaID, result.toString(), seqNum);
                    }
                } else if (msg.getType() == UDPMessage.Type.REPLICA_READY) {
                    ackUdpMessage(socket, packet, "REPLICA_READY");
                }
            }
        } catch (Exception e) {
            System.err.println("FE result listener error: " + e.getMessage());
        }
    }

    private void sendToAllRMs(String message) {
        try {
            DatagramSocket socket = new DatagramSocket();
            for (int rmPort : PortConfig.ALL_RMS) {
                sender.send(message, InetAddress.getByName("localhost"), rmPort, socket);
            }
            socket.close();
        } catch (Exception e) {
            System.err.println("FE→RM send error: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        FrontEnd fe = new FrontEnd();
        String url = "http://localhost:" + PortConfig.FE_SOAP + "/fe";
        Endpoint.publish(url, fe);
        System.out.println("FrontEnd published at " + url);
    }
}
