package server;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replica Manager (RM) — one instance per replica in the FT-DVRMS system.
 *
 * <p>Each RM is responsible for:
 * <ol>
 *   <li>Launching and monitoring its co-located replica via heartbeats.</li>
 *   <li>Participating in consensus voting when the FE reports Byzantine or crash faults.</li>
 *   <li>Replacing a faulty replica and coordinating state transfer from a healthy peer.</li>
 *   <li>Notifying the Sequencer, FE, and other RMs when a replacement replica is ready.</li>
 * </ol>
 *
 * <p><b>Workflow overview:</b>
 * <pre>
 *   FE detects fault
 *       │
 *       ├─ Byzantine (3 strikes) ──→ REPLACE_REQUEST to all RMs
 *       │                                 │
 *       │                                 ▼
 *       │                          RM broadcasts VOTE_BYZANTINE
 *       │                                 │
 *       ├─ Crash (timeout) ────────→ CRASH_SUSPECT to all RMs
 *       │                                 │
 *       │                                 ▼
 *       │                          Each RM verifies via heartbeat,
 *       │                          broadcasts VOTE_CRASH
 *       │                                 │
 *       ▼                                 ▼
 *   handleVote() collects votes from all RMs
 *       │
 *       ▼  (majority agrees)
 *   replaceReplica()
 *       │
 *       ├─ 1. Kill faulty replica
 *       ├─ 2. Launch fresh replica
 *       ├─ 3. Request state snapshot from healthy RM
 *       ├─ 4. Send INIT_STATE to new replica
 *       └─ 5. Broadcast REPLICA_READY to Sequencer, FE, and all RMs
 * </pre>
 *
 * <p>Usage: {@code java server.ReplicaManager <replicaId>}
 *
 * @see PortConfig for RM port assignments (7001–7004)
 * @see UDPMessage for message format definitions
 * @see ReliableUDPSender for ACK-based reliable UDP with exponential backoff
 */
public class ReplicaManager {

    private final int replicaId;
    private final int rmPort;
    private final int replicaPort;
    private Process replicaProcess;

    /**
     * Vote collection map: voteKey → (rmId → vote decision).
     * <p>voteKey format: {@code "VOTE_BYZANTINE:replicaId"} or {@code "VOTE_CRASH:replicaId"}.
     * <p>Vote decisions: {@code "AGREE"} for Byzantine, {@code "CRASH_CONFIRMED"} or
     * {@code "ALIVE"} for crash.
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> voteCollector =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap.KeySetView<String, Boolean> scheduledVoteEvaluation =
        ConcurrentHashMap.newKeySet();
    // In-flight replacement guard — keyed by targetId (not voteKey) so Byzantine and Crash
    // faults on the same replica are mutually exclusive and can't both trigger replaceReplica().
    // Cleared in finally so future independent faults on the same replica are still handled.
    private final ConcurrentHashMap.KeySetView<String, Boolean> replacementInProgress =
        ConcurrentHashMap.newKeySet();

    private static final int HEARTBEAT_INTERVAL_MS = 3000;
    private static final int HEARTBEAT_TIMEOUT_MS = 2000;
    private static final long VOTE_WINDOW_MS = 2000;
    private static final int RELIABLE_INIT_TIMEOUT_MS = 500;
    private static final int RELIABLE_MAX_RETRIES = 5;
    private final ReliableUDPSender sender = new ReliableUDPSender();

    /**
     * Creates a Replica Manager for the given replica ID.
     *
     * @param replicaId the replica ID (1–4), used to look up RM and replica ports
     *                  from {@link PortConfig#ALL_RMS} and {@link PortConfig#ALL_REPLICAS}
     */
    public ReplicaManager(int replicaId) {
        this.replicaId = replicaId;
        this.rmPort = PortConfig.ALL_RMS[replicaId - 1];
        this.replicaPort = PortConfig.ALL_REPLICAS[replicaId - 1];
    }

    /**
     * Starts the RM: launches the replica process, begins heartbeat monitoring
     * in a background thread, and enters the main message loop.
     */
    public void start() {
        launchReplica();
        new Thread(this::heartbeatLoop, "RM" + replicaId + "-Heartbeat").start();
        listenForMessages();
    }

    /**
     * Spawns the co-located replica as a subprocess via {@link ProcessBuilder}.
     * The replica runs {@code server.ReplicaLauncher} with the same classpath.
     */
    protected synchronized void launchReplica() {
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

    /**
     * Forcibly terminates the co-located replica process if it is still alive.
     */
    public synchronized void killReplica() {
        if (replicaProcess != null && replicaProcess.isAlive()) {
            try (DatagramSocket s = new DatagramSocket()) {
                String msg = "SHUTDOWN:" + replicaId;
                byte[] data = msg.getBytes(StandardCharsets.UTF_8);
                s.send(new DatagramPacket(data, data.length,
                    InetAddress.getByName("localhost"), replicaPort));
            } catch (Exception ignored) {}
            replicaProcess.destroyForcibly();
            System.out.println("RM" + replicaId + ": Replica killed");
        }
    }

    /** Stops this RM: terminates the co-located replica subprocess. */
    public void stop() {
        killReplica();
    }

    /**
     * Periodically sends heartbeat checks to the co-located replica.
     * Runs in a dedicated background thread with a 3-second interval.
     */
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

    /**
     * Sends a single {@code HEARTBEAT_CHECK} to the co-located replica and waits
     * for a {@code HEARTBEAT_ACK} response.
     *
     * @return {@code true} if the replica responded with HEARTBEAT_ACK within
     *         the timeout, {@code false} otherwise (replica may be crashed)
     */
    private boolean sendHeartbeat() {
        return sendHeartbeatTo(replicaPort);
    }

    /**
     * Sends a single {@code HEARTBEAT_CHECK} to a specific replica port and waits
     * for a {@code HEARTBEAT_ACK} response.
     *
     * @param targetPort the UDP port of the replica to check
     * @return {@code true} if the replica responded within the timeout
     */
    protected boolean sendHeartbeatTo(int targetPort) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(HEARTBEAT_TIMEOUT_MS);
            String msg = "HEARTBEAT_CHECK:" + replicaId;
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length,
                InetAddress.getByName("localhost"), targetPort));

            byte[] buf = new byte[8192];
            DatagramPacket reply = new DatagramPacket(buf, buf.length);
            socket.receive(reply);
            String response = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8);
            return response.startsWith("HEARTBEAT_ACK");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Main message loop — listens on the RM port for UDP messages from
     * the FE and other RMs, and dispatches to the appropriate handler.
     *
     * <p>Handled message types:
     * <ul>
     *   <li>{@code REPLACE_REQUEST} — FE detected 3 consecutive Byzantine faults</li>
     *   <li>{@code CRASH_SUSPECT} — FE timed out waiting for a replica response</li>
     *   <li>{@code VOTE_BYZANTINE} / {@code VOTE_CRASH} — vote from another RM</li>
     *   <li>{@code STATE_REQUEST} — another RM requesting a state snapshot</li>
     * </ul>
     */
    private void listenForMessages() {
        try (DatagramSocket socket = new DatagramSocket(rmPort)) {
            byte[] buf = new byte[8192];
            System.out.println("RM" + replicaId + " listening on port " + rmPort);

            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                try {
                    String raw = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    UDPMessage msg = UDPMessage.parse(raw);
                    maybeAckFaultNotification(msg, socket, packet);

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
                        case STATE_REQUEST: {
                            InetAddress fromAddr = packet.getAddress();
                            int fromPort = packet.getPort();
                            DatagramPacket frozenPacket = new DatagramPacket(new byte[0], 0, fromAddr, fromPort);
                            new Thread(() -> handleStateRequest(msg, socket, frozenPacket),
                                "RM" + replicaId + "-StateReq").start();
                            break;
                        }
                        default:
                            break;
                    }
                } catch (Exception e) {
                    System.err.println("RM" + replicaId + ": Ignoring malformed packet: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("RM" + replicaId + " error: " + e.getMessage());
        }
    }

    protected void maybeAckFaultNotification(
        UDPMessage msg, DatagramSocket socket, DatagramPacket sourcePacket) {
        UDPMessage.Type type = msg.getType();
        if (type != UDPMessage.Type.INCORRECT_RESULT
            && type != UDPMessage.Type.CRASH_SUSPECT
            && type != UDPMessage.Type.REPLACE_REQUEST
            && type != UDPMessage.Type.VOTE_BYZANTINE
            && type != UDPMessage.Type.VOTE_CRASH
            && type != UDPMessage.Type.STATE_REQUEST
            && type != UDPMessage.Type.REPLICA_READY) {
            return;
        }

        try {
            String ack = "ACK:" + type.name();
            byte[] ackData = ack.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(
                ackData, ackData.length, sourcePacket.getAddress(), sourcePacket.getPort()));
        } catch (Exception e) {
            System.err.println("RM" + replicaId + ": ACK send error: " + e.getMessage());
        }
    }

    private void broadcastToPeerRmsReliably(String message, String context) {
        for (int port : PortConfig.ALL_RMS) {
            if (port == rmPort) {
                continue;
            }
            final int targetPort = port;
            Thread voteSender = new Thread(() -> {
                try (DatagramSocket sendSocket = new DatagramSocket()) {
                    InetAddress localhost = InetAddress.getByName("localhost");
                    boolean acked = sender.send(message, localhost, targetPort, sendSocket);
                    if (!acked) {
                        System.err.println(
                            "RM" + replicaId + ": " + context + " not ACKed by RM port " + targetPort);
                    }
                } catch (Exception e) {
                    System.err.println(
                        "RM" + replicaId + ": " + context + " send error to RM port "
                            + targetPort + ": " + e.getMessage());
                }
            }, "RM" + replicaId + "-" + context + "-to-" + targetPort);
            voteSender.setDaemon(true);
            voteSender.start();
        }
    }

    private void sendAckToPacket(DatagramSocket socket, DatagramPacket packet, String token) {
        try {
            String ack = "ACK:" + token;
            byte[] ackData = ack.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(
                ackData, ackData.length, packet.getAddress(), packet.getPort()));
        } catch (Exception e) {
            System.err.println("RM" + replicaId + ": packet ACK send error: " + e.getMessage());
        }
    }

    private String normalizeReplicaId(String replicaIdToken) {
        if (replicaIdToken == null) {
            return null;
        }
        String trimmed = replicaIdToken.trim();
        if (trimmed.length() >= 2
            && (trimmed.charAt(0) == 'R' || trimmed.charAt(0) == 'r')
            && Character.isDigit(trimmed.charAt(1))) {
            return trimmed.substring(1);
        }
        return trimmed;
    }

    /**
     * Handles a {@code REPLACE_REQUEST} from the FE (Byzantine fault threshold reached).
     * Broadcasts a {@code VOTE_BYZANTINE} message to all RMs to initiate consensus.
     *
     * <p>Message format: {@code REPLACE_REQUEST:<faultyReplicaId>:BYZANTINE_THRESHOLD}
     *
     * @param msg    the parsed REPLACE_REQUEST message
     * @param socket the RM's listener socket used for sending votes
     */
    protected void handleByzantineReplace(UDPMessage msg, DatagramSocket socket) {
        if (msg.fieldCount() < 1) {
            return;
        }
        String faultyReplicaId = normalizeReplicaId(msg.getField(0));
        if (faultyReplicaId == null || faultyReplicaId.isEmpty()) {
            return;
        }
        System.out.println("RM" + replicaId + ": Byzantine replace requested for " + faultyReplicaId);

        // Broadcast VOTE_BYZANTINE to all RMs (including self) for consensus
        String vote = "VOTE_BYZANTINE:" + faultyReplicaId + ":" + replicaId;
        try {
            // Self-vote locally to avoid reliable self-send deadlock on the listener thread.
            handleVote(UDPMessage.parse(vote), socket);
        } catch (Exception e) {
            System.err.println("RM" + replicaId + ": failed to record self Byzantine vote: " + e.getMessage());
        }
        broadcastToPeerRmsReliably(vote, "VOTE_BYZANTINE");
    }

    /**
     * Handles a {@code CRASH_SUSPECT} from the FE (replica did not respond in time).
     * Independently verifies by sending a heartbeat to the suspected replica,
     * then broadcasts the verdict ({@code ALIVE} or {@code CRASH_CONFIRMED}) to all RMs.
     *
     * <p>Message format: {@code CRASH_SUSPECT:<reqID>:<seqNum>:<suspectedReplicaId>}
     *
     * @param msg    the parsed CRASH_SUSPECT message
     * @param socket the RM's listener socket used for sending votes
     */
    protected void handleCrashSuspect(UDPMessage msg, DatagramSocket socket) {
        if (msg.fieldCount() < 3) {
            return;
        }
        String suspectedId = normalizeReplicaId(msg.getField(2)); // field(2) = suspectedReplicaId
        if (suspectedId == null || suspectedId.isEmpty()) {
            return;
        }
        // Heartbeat the suspected replica's port, not our own
        int suspectedIndex;
        try {
            suspectedIndex = Integer.parseInt(suspectedId) - 1;
        } catch (NumberFormatException e) {
            System.err.println("RM" + replicaId + ": invalid suspected replica ID " + suspectedId);
            return;
        }
        if (suspectedIndex < 0 || suspectedIndex >= PortConfig.ALL_REPLICAS.length) {
            System.err.println("RM" + replicaId + ": out-of-range suspected replica ID " + suspectedId);
            return;
        }
        int suspectedPort = PortConfig.ALL_REPLICAS[suspectedIndex];
        boolean alive = sendHeartbeatTo(suspectedPort);
        String vote = alive
            ? "VOTE_CRASH:" + suspectedId + ":ALIVE:" + replicaId
            : "VOTE_CRASH:" + suspectedId + ":CRASH_CONFIRMED:" + replicaId;
        // Broadcast verdict to all peer RMs and record self vote locally.
        try {
            handleVote(UDPMessage.parse(vote), socket);
        } catch (Exception e) {
            System.err.println("RM" + replicaId + ": failed to record self crash vote: " + e.getMessage());
        }
        broadcastToPeerRmsReliably(vote, "VOTE_CRASH");
    }

    /**
     * Collects and tallies votes from all RMs. When a strict majority of received
     * votes agree (AGREE or CRASH_CONFIRMED), triggers replica replacement.
     *
     * <p>Vote formats:
     * <ul>
     *   <li>{@code VOTE_BYZANTINE:<targetId>:<voterId>} — implicit "AGREE"</li>
     *   <li>{@code VOTE_CRASH:<targetId>:<ALIVE|CRASH_CONFIRMED>:<voterId>}</li>
     * </ul>
     *
     * <p>Majority rule: evaluate votes after a bounded vote window and require
     * {@code agreeCount > totalVotes / 2} where {@code totalVotes} are the votes
     * received in that window (reachable RMs). Only the RM responsible for the
     * target replica performs replacement.
     *
     * @param msg    the parsed vote message
     * @param socket the RM's listener socket (unused but kept for handler signature)
     */
    protected void handleVote(UDPMessage msg, DatagramSocket socket) {
        if (msg.fieldCount() < 2) {
            return;
        }
        String voteType = msg.getType().name();
        String targetId = normalizeReplicaId(msg.getField(0));
        if (targetId == null || targetId.isEmpty()) {
            return;
        }
        String voteKey = voteType + ":" + targetId;

        // Vote format: VOTE_BYZANTINE:<targetId>:<voterId>
        //              VOTE_CRASH:<targetId>:<decision>:<voterId>
        String voterDecision;
        String voterId;
        if (msg.getType() == UDPMessage.Type.VOTE_CRASH) {
            if (msg.fieldCount() < 3) {
                return;
            }
            voterDecision = msg.getField(1);            // ALIVE or CRASH_CONFIRMED
            voterId = msg.getField(2);                   // sender RM id
        } else {
            voterDecision = "AGREE";                     // Byzantine votes are implicit agree
            voterId = msg.getField(1);                   // sender RM id
        }

        // Record this vote keyed by the sender's RM identity
        voteCollector.computeIfAbsent(voteKey, k -> new ConcurrentHashMap<>())
            .put("RM" + voterId, voterDecision);

        if (scheduledVoteEvaluation.add(voteKey)) {
            Thread evaluator = new Thread(() -> {
                try {
                    Thread.sleep(voteWindowMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    scheduledVoteEvaluation.remove(voteKey);
                    evaluateVoteWindow(voteKey);
                }
            }, "RM" + replicaId + "-VoteWindow-" + voteKey);
            evaluator.setDaemon(true);
            evaluator.start();
        }
    }

    protected long voteWindowMs() {
        return VOTE_WINDOW_MS;
    }

    protected void evaluateVoteWindow(String voteKey) {
        ConcurrentHashMap<String, String> votes = voteCollector.remove(voteKey);
        if (votes == null || votes.isEmpty()) {
            return;
        }

        int totalVotes = votes.size();
        long agreeCount = votes.values().stream()
            .filter(v -> v.equals("AGREE") || v.equals("CRASH_CONFIRMED"))
            .count();

        if (agreeCount <= totalVotes / 2) {
            return;
        }

        int splitAt = voteKey.indexOf(':');
        if (splitAt < 0 || splitAt == voteKey.length() - 1) {
            return;
        }
        String targetId = voteKey.substring(splitAt + 1);
        if (targetId.equals(String.valueOf(replicaId)) && replacementInProgress.add(targetId)) {
            try {
                replaceReplica();
            } finally {
                replacementInProgress.remove(targetId);
            }
        }
    }

    /**
     * Handles a {@code STATE_REQUEST} from another RM that needs a state snapshot.
     * Forwards the request to the co-located replica, waits for a {@code STATE_TRANSFER}
     * response, and relays it back to the requesting RM.
     *
     * <p>Flow: requesting RM → this RM → co-located replica → this RM → requesting RM
     *
     * @param msg    the parsed STATE_REQUEST message
     * @param socket the RM's listener socket used to send the response
     * @param from   the original packet (source address of the requesting RM)
     */
    protected void handleStateRequest(UDPMessage msg, DatagramSocket socket, DatagramPacket from) {
        try (DatagramSocket reqSocket = new DatagramSocket()) {
            // Forward STATE_REQUEST to the co-located replica
            String stateReq = "STATE_REQUEST:" + replicaId;
            InetAddress localhost = InetAddress.getByName("localhost");
            boolean reqAcked = sender.send(stateReq, localhost, replicaPort, reqSocket);
            if (!reqAcked) {
                System.err.println("RM" + replicaId + ": STATE_REQUEST to replica not ACKed");
                return;
            }

            // Wait for STATE_TRANSFER response from the replica
            reqSocket.setSoTimeout(10000);
            byte[] buf = new byte[65535]; // snapshots can be large
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            reqSocket.receive(response);
            sendAckToPacket(reqSocket, response, "STATE_TRANSFER");
            String stateResponse = new String(response.getData(), 0, response.getLength(),
                StandardCharsets.UTF_8);

            // Relay the snapshot back to the requesting RM
            try (DatagramSocket relaySocket = new DatagramSocket()) {
                boolean relayAcked = sender.send(stateResponse, from.getAddress(), from.getPort(), relaySocket);
                if (!relayAcked) {
                    System.err.println("RM" + replicaId + ": STATE_TRANSFER relay not ACKed by requester RM");
                }
            }
        } catch (Exception e) {
            System.err.println("RM" + replicaId + ": State request handling failed: " + e.getMessage());
        }
    }

    /**
     * Replaces the co-located replica with a fresh instance and restores its state.
     *
     * <p>Replacement workflow:
     * <ol>
     *   <li>Kill the faulty replica process (Byzantine) or skip (crash — already dead).</li>
     *   <li>Launch a fresh replica via {@link #launchReplica()}.</li>
     *   <li>Request a state snapshot from the lowest-ID healthy RM.</li>
     *   <li>Send {@code INIT_STATE} with the snapshot to the new replica;
     *       the replica replies with ACK containing the lastSeqNum.</li>
     *   <li>Broadcast {@code REPLICA_READY} to Sequencer (for replay catch-up),
     *       FE, and all RMs.</li>
     * </ol>
     *
     * <p>REPLICA_READY format: {@code REPLICA_READY:<replicaId>:localhost:<port>:<lastSeqNum>}
     */
    protected void replaceReplica() {
        System.out.println("RM" + replicaId + ": Starting replica replacement");

        // Step 1–2: Kill faulty replica and launch fresh one
        killReplica();
        launchReplica();

        // Step 3: Request state snapshot from a healthy peer RM
        String snapshot = requestStateFromHealthyReplica();
        int lastSeqNum = -1; // default: no state → Sequencer replays everything

        // Step 4: Transfer state to the new replica
        if (snapshot != null) {
            lastSeqNum = initializeReplicaState(snapshot);
        }

        // Step 5: Notify Sequencer, FE, and all RMs that the replica is ready
        notifyReplicaReady(lastSeqNum);
    }

    protected int initializeReplicaState(String snapshot) {
        try (DatagramSocket socket = new DatagramSocket()) {
            String initMsg = "INIT_STATE:" + snapshot;
            byte[] data = initMsg.getBytes(StandardCharsets.UTF_8);
            InetAddress localhost = InetAddress.getByName("localhost");
            DatagramPacket initPacket = new DatagramPacket(data, data.length, localhost, replicaPort);

            int timeout = RELIABLE_INIT_TIMEOUT_MS;
            for (int attempt = 0; attempt <= RELIABLE_MAX_RETRIES; attempt++) {
                try {
                    socket.send(initPacket);
                    socket.setSoTimeout(timeout);

                    // Wait for ACK — format: ACK:INIT_STATE:<replicaId>:<lastSeqNum>
                    byte[] buf = new byte[8192];
                    DatagramPacket ack = new DatagramPacket(buf, buf.length);
                    socket.receive(ack);
                    String ackStr = new String(ack.getData(), 0, ack.getLength(), StandardCharsets.UTF_8);
                    if (!ackStr.startsWith("ACK:INIT_STATE:")) {
                        continue;
                    }
                    sendAckToPacket(socket, ack, "INIT_STATE");
                    String[] ackParts = ackStr.split(":");
                    int lastSeqNum = -1;
                    if (ackParts.length >= 4) {
                        lastSeqNum = Integer.parseInt(ackParts[3]);
                    }
                    System.out.println("RM" + replicaId + ": State transfer complete, lastSeq=" + lastSeqNum);
                    return lastSeqNum;
                } catch (SocketTimeoutException e) {
                    timeout *= 2;
                }
            }
            System.err.println("RM" + replicaId + ": State transfer ACK not received after retries");
            return -1;
        } catch (Exception e) {
            System.err.println("RM" + replicaId + ": State transfer failed: " + e.getMessage());
            return -1;
        }
    }

    protected String buildReplicaReadyMessage(int lastSeqNum) {
        return "REPLICA_READY:" + replicaId + ":localhost:" + replicaPort + ":" + lastSeqNum;
    }

    protected void notifyReplicaReady(int lastSeqNum) {
        try (DatagramSocket socket = new DatagramSocket()) {
            String readyMsg = buildReplicaReadyMessage(lastSeqNum);
            InetAddress localhost = InetAddress.getByName("localhost");
            boolean sequencerAcked = sender.send(readyMsg, localhost, PortConfig.SEQUENCER, socket);
            if (!sequencerAcked) {
                System.err.println("RM" + replicaId + ": REPLICA_READY not ACKed by Sequencer");
            }
            boolean feAcked = sender.send(readyMsg, localhost, PortConfig.FE_UDP, socket);
            if (!feAcked) {
                System.err.println("RM" + replicaId + ": REPLICA_READY not ACKed by FE");
            }
            for (int rmPort : PortConfig.ALL_RMS) {
                if (rmPort == this.rmPort) {
                    continue;
                }
                boolean rmAcked = sender.send(readyMsg, localhost, rmPort, socket);
                if (!rmAcked) {
                    System.err.println("RM" + replicaId + ": REPLICA_READY not ACKed by RM port " + rmPort);
                }
            }
        } catch (Exception e) {
            System.err.println("RM" + replicaId + ": REPLICA_READY send failed: " + e.getMessage());
        }
    }

    /**
     * Requests a state snapshot from the lowest-ID healthy RM (skipping self).
     * Tries each RM in order; returns the first successful snapshot.
     *
     * <p>Flow: this RM → target RM (STATE_REQUEST) → target RM's replica (STATE_REQUEST)
     * → target RM (STATE_TRANSFER) → this RM (STATE_TRANSFER)
     *
     * @return the snapshot string (format: {@code mtlSnap|wpgSnap|bnfSnap}),
     *         or {@code null} if no healthy RM responded
     */
    protected String requestStateFromHealthyReplica() {
        for (int i = 0; i < PortConfig.ALL_RMS.length; i++) {
            int targetRmPort = PortConfig.ALL_RMS[i];
            if (targetRmPort == rmPort) continue; // skip self

            try (DatagramSocket socket = new DatagramSocket()) {
                String req = "STATE_REQUEST:" + replicaId;
                InetAddress localhost = InetAddress.getByName("localhost");
                boolean requestAcked = sender.send(req, localhost, targetRmPort, socket);
                if (!requestAcked) {
                    System.err.println("RM" + replicaId + ": STATE_REQUEST not ACKed by RM port " + targetRmPort);
                    continue;
                }

                socket.setSoTimeout(10000);
                byte[] buf = new byte[65535];
                DatagramPacket response = new DatagramPacket(buf, buf.length);
                socket.receive(response);
                sendAckToPacket(socket, response, "STATE_TRANSFER");
                String raw = new String(response.getData(), 0, response.getLength(),
                    StandardCharsets.UTF_8);

                // STATE_TRANSFER:<sourceReplicaId>:<mtlSnap|wpgSnap|bnfSnap>
                if (raw.startsWith("STATE_TRANSFER:")) {
                    UDPMessage stateMsg = UDPMessage.parse(raw);
                    return stateMsg.getField(1); // the snapshot data
                }
            } catch (Exception e) {
                System.err.println("RM" + replicaId + ": State request to RM port "
                    + targetRmPort + " failed, trying next");
            }
        }
        System.err.println("RM" + replicaId + ": No healthy RM responded with state");
        return null;
    }

    /**
     * Entry point — starts a single Replica Manager instance.
     *
     * @param args command-line arguments: {@code <replicaId>} (1–4)
     */
    public static void main(String[] args) {
        int id = Integer.parseInt(args[0]);
        new ReplicaManager(id).start();
    }
}
