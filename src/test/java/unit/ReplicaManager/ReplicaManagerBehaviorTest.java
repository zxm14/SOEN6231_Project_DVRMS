package unit.ReplicaManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.PortConfig;
import server.ReplicaManager;
import server.UDPMessage;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicaManagerBehaviorTest {

    private int[] originalRmPorts;
    private int[] originalReplicaPorts;

    @BeforeEach
    void savePortArrays() {
        originalRmPorts = Arrays.copyOf(PortConfig.ALL_RMS, PortConfig.ALL_RMS.length);
        originalReplicaPorts = Arrays.copyOf(PortConfig.ALL_REPLICAS, PortConfig.ALL_REPLICAS.length);
    }

    @AfterEach
    void restorePortArrays() {
        System.arraycopy(originalRmPorts, 0, PortConfig.ALL_RMS, 0, originalRmPorts.length);
        System.arraycopy(originalReplicaPorts, 0, PortConfig.ALL_REPLICAS, 0, originalReplicaPorts.length);
    }

    @Test
    void voteCrash_usesReachableMajorityWithinWindow() throws Exception {
        VoteTestReplicaManager rm = new VoteTestReplicaManager(2);

        rm.acceptVote(new UDPMessage(UDPMessage.Type.VOTE_CRASH, "2", "CRASH_CONFIRMED", "1"));
        rm.acceptVote(new UDPMessage(UDPMessage.Type.VOTE_CRASH, "2", "CRASH_CONFIRMED", "3"));

        assertTrue(rm.replacementTriggered.await(1000, TimeUnit.MILLISECONDS));
        assertTrue(rm.replaced.get());
    }

    @Test
    void voteByzantine_usesReachableMajorityWithinWindow() throws Exception {
        VoteTestReplicaManager rm = new VoteTestReplicaManager(2);

        rm.acceptVote(new UDPMessage(UDPMessage.Type.VOTE_BYZANTINE, "2", "1"));
        rm.acceptVote(new UDPMessage(UDPMessage.Type.VOTE_BYZANTINE, "2", "4"));

        assertTrue(rm.replacementTriggered.await(1000, TimeUnit.MILLISECONDS));
        assertTrue(rm.replaced.get());
    }

    @Test
    void malformedVotes_areIgnoredWithoutTriggeringReplacement() throws Exception {
        VoteTestReplicaManager rm = new VoteTestReplicaManager(2);

        // Missing voter ID
        rm.acceptVote(new UDPMessage(UDPMessage.Type.VOTE_BYZANTINE, "2"));
        // Missing voter ID for VOTE_CRASH (needs: targetId, decision, voterId)
        rm.acceptVote(new UDPMessage(UDPMessage.Type.VOTE_CRASH, "2", "CRASH_CONFIRMED"));

        assertFalse(rm.replacementTriggered.await(250, TimeUnit.MILLISECONDS));
        assertFalse(rm.replaced.get());
    }

    @Test
    void malformedFaultNotifications_areIgnoredWithoutExceptions() throws Exception {
        VoteTestReplicaManager rm = new VoteTestReplicaManager(2);
        try (DatagramSocket outbound = new DatagramSocket()) {
            assertDoesNotThrow(() ->
                rm.handleByzantineReplaceDirect(new UDPMessage(UDPMessage.Type.REPLACE_REQUEST), outbound));
            assertDoesNotThrow(() ->
                rm.handleCrashSuspectDirect(
                    new UDPMessage(UDPMessage.Type.CRASH_SUSPECT, "REQ-1", "5"), outbound));
        }
        assertEquals(-1, rm.lastHeartbeatTargetPort);
    }

    @Test
    void crashSuspect_parsesNumericIdAndBroadcastsVote() throws Exception {
        VoteTestReplicaManager rm = new VoteTestReplicaManager(2);
        rm.heartbeatResponse = false;
        int expectedReplicaPort = 61234;
        PortConfig.ALL_REPLICAS[2] = expectedReplicaPort; // suspected replicaID=3

        DatagramSocket[] rmListeners = new DatagramSocket[PortConfig.ALL_RMS.length];
        for (int i = 0; i < rmListeners.length; i++) {
            rmListeners[i] = new DatagramSocket(0);
            rmListeners[i].setSoTimeout(1000);
            PortConfig.ALL_RMS[i] = rmListeners[i].getLocalPort();
        }

        try {
            try (DatagramSocket outbound = new DatagramSocket()) {
                rm.handleCrashSuspectDirect(
                    new UDPMessage(UDPMessage.Type.CRASH_SUSPECT, "REQ-1", "5", "3"), outbound);
            }

            assertEquals(expectedReplicaPort, rm.lastHeartbeatTargetPort);
            for (DatagramSocket listener : rmListeners) {
                byte[] buf = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                listener.receive(packet);
                String vote = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                assertEquals("VOTE_CRASH:3:CRASH_CONFIRMED:2", vote);
            }
        } finally {
            for (DatagramSocket listener : rmListeners) {
                if (listener != null && !listener.isClosed()) {
                    listener.close();
                }
            }
        }
    }

    @Test
    void replaceReplica_requestsStateInitializesAndBuildsReadyMessage() {
        ReplaceFlowReplicaManager rm = new ReplaceFlowReplicaManager(2);
        rm.replaceReplicaDirect();

        assertTrue(rm.killCalled);
        assertTrue(rm.launchCalled);
        assertEquals("snap-mtl|snap-wpg|snap-bnf", rm.initializedSnapshot);
        assertEquals(42, rm.readyLastSeq);
        assertEquals("REPLICA_READY:2:localhost:" + PortConfig.ALL_REPLICAS[1] + ":42", rm.readyMessage);
    }

    @Test
    void feFaultNotification_getsAckFromRmPath() throws Exception {
        VoteTestReplicaManager rm = new VoteTestReplicaManager(1);
        try (DatagramSocket rmSocket = new DatagramSocket(0);
             DatagramSocket senderSocket = new DatagramSocket(0)) {
            senderSocket.setSoTimeout(1000);
            DatagramPacket source = new DatagramPacket(
                new byte[0], 0, InetAddress.getByName("localhost"), senderSocket.getLocalPort());

            rm.sendAckForFaultNotification(
                new UDPMessage(UDPMessage.Type.CRASH_SUSPECT, "REQ-9", "11", "2"),
                rmSocket, source);

            byte[] ackBuf = new byte[1024];
            DatagramPacket ack = new DatagramPacket(ackBuf, ackBuf.length);
            senderSocket.receive(ack);
            String ackMsg = new String(ack.getData(), 0, ack.getLength(), StandardCharsets.UTF_8);
            assertTrue(ackMsg.startsWith("ACK:"));
        }
    }

    private static class VoteTestReplicaManager extends ReplicaManager {
        private final AtomicBoolean replaced = new AtomicBoolean(false);
        private final CountDownLatch replacementTriggered = new CountDownLatch(1);
        private volatile long voteWindowMs = 80;
        private volatile boolean heartbeatResponse = false;
        private volatile int lastHeartbeatTargetPort = -1;

        VoteTestReplicaManager(int replicaId) {
            super(replicaId);
        }

        void acceptVote(UDPMessage msg) throws Exception {
            try (DatagramSocket socket = new DatagramSocket()) {
                handleVote(msg, socket);
            }
        }

        void handleCrashSuspectDirect(UDPMessage msg, DatagramSocket socket) {
            handleCrashSuspect(msg, socket);
        }

        void handleByzantineReplaceDirect(UDPMessage msg, DatagramSocket socket) {
            handleByzantineReplace(msg, socket);
        }

        void sendAckForFaultNotification(
            UDPMessage msg, DatagramSocket rmSocket, DatagramPacket sourcePacket) {
            maybeAckFaultNotification(msg, rmSocket, sourcePacket);
        }

        @Override
        protected long voteWindowMs() {
            return voteWindowMs;
        }

        @Override
        protected void replaceReplica() {
            replaced.set(true);
            replacementTriggered.countDown();
        }

        @Override
        protected boolean sendHeartbeatTo(int targetPort) {
            lastHeartbeatTargetPort = targetPort;
            return heartbeatResponse;
        }
    }

    private static class ReplaceFlowReplicaManager extends ReplicaManager {
        private boolean killCalled;
        private boolean launchCalled;
        private String initializedSnapshot;
        private int readyLastSeq = Integer.MIN_VALUE;
        private String readyMessage;

        ReplaceFlowReplicaManager(int replicaId) {
            super(replicaId);
        }

        void replaceReplicaDirect() {
            replaceReplica();
        }

        @Override
        protected void killReplica() {
            killCalled = true;
        }

        @Override
        protected void launchReplica() {
            launchCalled = true;
        }

        @Override
        protected String requestStateFromHealthyReplica() {
            return "snap-mtl|snap-wpg|snap-bnf";
        }

        @Override
        protected int initializeReplicaState(String snapshot) {
            initializedSnapshot = snapshot;
            return 42;
        }

        @Override
        protected void notifyReplicaReady(int lastSeqNum) {
            readyLastSeq = lastSeqNum;
            readyMessage = buildReplicaReadyMessage(lastSeqNum);
        }
    }
}
