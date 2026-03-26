package unit.FrontEnd;

import org.junit.jupiter.api.*;
import server.FrontEnd;
import server.PortConfig;
import server.ReliableUDPSender;

import java.lang.reflect.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FrontEnd.
 *
 * <p>Owner: [Student 1]
 *
 * <p>These tests verify the FE's internal logic in isolation:
 * <ul>
 *   <li>Majority voting among replica results</li>
 *   <li>Byzantine counter increment / reset per replica</li>
 *   <li>REPLACE_REQUEST broadcast after 3 consecutive Byzantine strikes</li>
 *   <li>CRASH_SUSPECT broadcast for replicas that never respond</li>
 * </ul>
 */
class FrontEndTest {

    private static FrontEnd fe;
    private static final List<String> capturedMessages = new CopyOnWriteArrayList<>();

    /**
     * A spy that captures messages instead of sending real UDP packets.
     */
    static class CapturingSender extends ReliableUDPSender {
        @Override
        public boolean send(String message, InetAddress address, int port, DatagramSocket socket) {
            capturedMessages.add(message);
            return true; // pretend ACK received
        }
    }

    @BeforeAll
    static void setup() throws Exception {
        System.setProperty("dvrms.disable.udp", "true");
        fe = new FrontEnd(new CapturingSender());
    }

    @BeforeEach
    void resetState() throws Exception {
        capturedMessages.clear();
        Field bcField = FrontEnd.class.getDeclaredField("byzantineCount");
        bcField.setAccessible(true);
        ((ConcurrentHashMap<?, ?>) bcField.get(fe)).clear();
    }

    // ===== Reflection helpers =====

    private static Object newRequestContext(String reqID) throws Exception {
        Class<?> ctxClass = Class.forName("server.FrontEnd$RequestContext");
        Constructor<?> ctor = ctxClass.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(reqID);
    }

    private static void addResult(Object ctx, String replicaID, String result, int seqNum) throws Exception {
        Method m = ctx.getClass().getDeclaredMethod("addResult", String.class, String.class, int.class);
        m.setAccessible(true);
        m.invoke(ctx, replicaID, result, seqNum);
    }

    private static String awaitMajority(Object ctx, long timeoutMs) throws Exception {
        Method m = ctx.getClass().getDeclaredMethod("awaitMajority", long.class);
        m.setAccessible(true);
        return (String) m.invoke(ctx, timeoutMs);
    }

    private void callProcessResults(Object ctx, String majorityResult) throws Exception {
        Class<?> ctxClass = Class.forName("server.FrontEnd$RequestContext");
        Method m = FrontEnd.class.getDeclaredMethod("processResults", ctxClass, String.class);
        m.setAccessible(true);

        // Put ctx in pendingRequests so processResults can remove it
        Field prField = FrontEnd.class.getDeclaredField("pendingRequests");
        prField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Object> pr = (ConcurrentHashMap<String, Object>) prField.get(fe);
        Field reqIdField = ctxClass.getDeclaredField("requestID");
        reqIdField.setAccessible(true);
        pr.put((String) reqIdField.get(ctx), ctx);

        m.invoke(fe, ctx, majorityResult);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, AtomicInteger> getByzantineCount() throws Exception {
        Field f = FrontEnd.class.getDeclaredField("byzantineCount");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, AtomicInteger>) f.get(fe);
    }

    // ===== Tests =====

    @Test
    void majorityVoting_returnsCorrectResult() throws Exception {
        Object ctx = newRequestContext("REQ-MV-1");

        // Replicas 1 and 2 agree; replica 3 disagrees
        addResult(ctx, "1", "SUCCESS: Reserved WPG1001", 1);
        addResult(ctx, "2", "SUCCESS: Reserved WPG1001", 1);
        addResult(ctx, "3", "BYZANTINE_RANDOM_XYZ", 1);

        // awaitMajority should return immediately (2 matching = f+1)
        String result = awaitMajority(ctx, 500);
        assertEquals("SUCCESS: Reserved WPG1001", result);
    }

    @Test
    void byzantineCounter_incrementsOnMismatch_resetsOnMatch() throws Exception {
        ConcurrentHashMap<String, AtomicInteger> bc = getByzantineCount();

        // Round 1: replicas 1,2 agree; replica 3 mismatches
        Object ctx1 = newRequestContext("REQ-BC-1");
        addResult(ctx1, "1", "GOOD", 1);
        addResult(ctx1, "2", "GOOD", 1);
        addResult(ctx1, "3", "BAD", 1);
        callProcessResults(ctx1, "GOOD");

        assertEquals(1, bc.get("3").get(), "Replica 3 mismatch count should be 1");
        assertEquals(0, bc.get("1").get(), "Replica 1 match count should reset to 0");

        // Round 2: all replicas agree → replica 3 counter resets to 0
        Object ctx2 = newRequestContext("REQ-BC-2");
        addResult(ctx2, "1", "GOOD2", 2);
        addResult(ctx2, "2", "GOOD2", 2);
        addResult(ctx2, "3", "GOOD2", 2);
        callProcessResults(ctx2, "GOOD2");

        assertEquals(0, bc.get("3").get(), "Replica 3 should reset to 0 after matching");
    }

    @Test
    void byzantineThreshold_triggersReplaceRequest() throws Exception {
        ConcurrentHashMap<String, AtomicInteger> bc = getByzantineCount();

        // Drive 3 consecutive mismatches for replica 3
        for (int i = 1; i <= 3; i++) {
            Object ctx = newRequestContext("REQ-BT-" + i);
            addResult(ctx, "1", "GOOD", i);
            addResult(ctx, "2", "GOOD", i);
            addResult(ctx, "3", "BAD-" + i, i);
            callProcessResults(ctx, "GOOD");
        }

        assertEquals(3, bc.get("3").get(), "Replica 3 should have 3 strikes");

        boolean hasReplaceRequest = capturedMessages.stream()
                .anyMatch(m -> m.startsWith("REPLACE_REQUEST:3:"));
        assertTrue(hasReplaceRequest,
                "Expected REPLACE_REQUEST for replica 3 after 3 strikes, captured: " + capturedMessages);
    }

    @Test
    void crashSuspect_reportedForNonRespondingReplica() throws Exception {
        // Only replicas 1 and 2 respond; 3 and 4 never respond
        Object ctx = newRequestContext("REQ-CS-1");
        addResult(ctx, "1", "RESULT_OK", 5);
        addResult(ctx, "2", "RESULT_OK", 5);
        callProcessResults(ctx, "RESULT_OK");

        boolean hasCrash3 = capturedMessages.stream()
                .anyMatch(m -> m.contains("CRASH_SUSPECT") && m.contains(":3"));
        boolean hasCrash4 = capturedMessages.stream()
                .anyMatch(m -> m.contains("CRASH_SUSPECT") && m.contains(":4"));
        assertTrue(hasCrash3, "Expected CRASH_SUSPECT for replica 3, captured: " + capturedMessages);
        assertTrue(hasCrash4, "Expected CRASH_SUSPECT for replica 4, captured: " + capturedMessages);
    }
}
