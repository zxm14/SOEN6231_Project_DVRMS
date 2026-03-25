package unit.FrontEnd;

import org.junit.jupiter.api.Test;
import server.FrontEnd;

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
 *
 * <p>Tip: set {@code System.setProperty("dvrms.disable.udp", "true")} before
 * constructing any {@link server.VehicleReservationWS} or {@link FrontEnd} instance
 * to suppress background UDP listener threads during unit tests.
 *
 * <p>Tip: {@code FrontEnd.RequestContext} is package-private — access it via a
 * test subclass or reflection, or expose a minimal test-only constructor if needed.
 */
class FrontEndTest {

    @Test
    void majorityVoting_returnsCorrectResult() {
        // TODO Student 1:
        // 1. Create a FrontEnd.RequestContext for a known requestID.
        // 2. Call addResult() for replicas "1", "2", "3" where "1" and "2" agree.
        // 3. Assert awaitMajority(500) returns the agreed result immediately
        //    (should not block the full timeout because 2/4 replicas already match).
        fail("Not implemented yet — Student 1");
    }

    @Test
    void byzantineCounter_incrementsOnMismatch_resetsOnMatch() {
        // TODO Student 1:
        // 1. Use reflection or a test subclass to call processResults() on a FrontEnd instance.
        // 2. Provide a majority result and one mismatching replica → verify byzantineCount = 1.
        // 3. On the next call where that replica matches → verify byzantineCount resets to 0.
        fail("Not implemented yet — Student 1");
    }

    @Test
    void byzantineThreshold_triggersReplaceRequest() {
        // TODO Student 1:
        // 1. Drive processResults() 3 times with the same replica ID mismatching each time.
        // 2. Capture UDP packets on PortConfig.ALL_RMS ports.
        // 3. Verify a REPLACE_REQUEST:<replicaID>:BYZANTINE_THRESHOLD message
        //    is broadcast to all 4 RM ports on the 3rd strike.
        fail("Not implemented yet — Student 1");
    }

    @Test
    void crashSuspect_reportedForNonRespondingReplica() {
        // TODO Student 1:
        // 1. Build a RequestContext where only replicas "1" and "2" have responded.
        // 2. Call processResults() with majority = replicas "1" and "2".
        // 3. Verify CRASH_SUSPECT:<reqID>:<seqNum>:3 and CRASH_SUSPECT:...:4
        //    are sent to all RM ports for the two non-responding replicas.
        fail("Not implemented yet — Student 1");
    }
}
