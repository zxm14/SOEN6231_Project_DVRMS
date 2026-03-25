package unit.Sequencer;

import org.junit.jupiter.api.Test;
import server.Sequencer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Sequencer.
 *
 * <p>Owner: [Student 3]
 *
 * <p>These tests verify the Sequencer's internal logic in isolation:
 * <ul>
 *   <li>Sequence numbers are assigned monotonically (0, 1, 2, …)</li>
 *   <li>Each REQUEST is multicast as EXECUTE to all replica addresses</li>
 *   <li>NACK causes replay of the requested range from historyBuffer</li>
 *   <li>REPLICA_READY triggers catch-up replay and registers the new address</li>
 * </ul>
 *
 * <p>Tip: {@code Sequencer.start()} binds port 9100 — do NOT call start() in unit tests.
 * Instead, test the private handler methods via a test subclass that overrides or exposes them,
 * or use reflection to call {@code handleRequest}, {@code handleNack}, {@code handleReplicaReady}
 * directly.
 *
 * <p>Tip: replace {@code replicaAddresses} (a {@code CopyOnWriteArrayList}) with test sockets
 * using reflection so multicasts land on ports you can listen on.
 */
class SequencerTest {

    @Test
    void requestHandling_assignsMonotonicallyIncreasingSeqNums() {
        // TODO Student 3:
        // 1. Create a Sequencer instance (do NOT call start()).
        // 2. Use reflection or a test subclass to call handleRequest() 3 times.
        // 3. Verify the EXECUTE messages produced carry seq# 0, 1, 2 in order.
        //    You can capture them by pointing replicaAddresses at local test sockets.
        fail("Not implemented yet — Student 3");
    }

    @Test
    void nackHandling_replaysHistoryBufferForMissedRange() {
        // TODO Student 3:
        // 1. Use reflection to pre-populate historyBuffer with entries for seq 0, 1, 2.
        // 2. Construct a NACK UDPMessage: NACK:<replicaId>:0:1.
        // 3. Call handleNack() with a DatagramPacket whose source is a local test socket.
        // 4. Assert that the test socket receives exactly 2 messages: seq 0 and seq 1.
        fail("Not implemented yet — Student 3");
    }

    @Test
    void replicaReady_triggersReplayAndUpdatesReplicaList() {
        // TODO Student 3:
        // 1. Pre-populate historyBuffer with entries for seq 0, 1, 2 (via reflection).
        // 2. Set sequenceCounter to 3 (via reflection).
        // 3. Bind a local test socket on an ephemeral port to act as the new replica.
        // 4. Call handleReplicaReady() with REPLICA_READY:<id>:localhost:<port>:1
        //    (lastSeq=1 means replay should send seq 2 only).
        // 5. Assert the test socket receives exactly one message (seq 2).
        // 6. Assert replicaAddresses contains the new address.
        fail("Not implemented yet — Student 3");
    }
}
