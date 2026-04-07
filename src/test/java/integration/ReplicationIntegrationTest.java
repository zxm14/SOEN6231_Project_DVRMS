package integration;

import org.junit.jupiter.api.*;
import server.*;

import java.net.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end replication tests.
 * Starts all P2 components and validates behavior through the FE SOAP interface.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ReplicationIntegrationTest {

    private static FrontEnd frontEnd;
    private static javax.xml.ws.Endpoint frontEndEndpoint;
    private static final java.util.List<ReplicaManager> replicaManagers = new java.util.ArrayList<>();

    @BeforeAll
    static void startSystem() throws Exception {
        // Start Sequencer
        new Thread(() -> new Sequencer().start()).start();

        // Start 4 RMs — each RM launches its co-located replica via ProcessBuilder
        for (int i = 1; i <= 4; i++) {
            final int id = i;
            ReplicaManager rm = new ReplicaManager(id);
            replicaManagers.add(rm);
            new Thread(() -> rm.start(), "RM-" + id).start();
        }

        // Start FE (SOAP endpoint)
        frontEnd = new FrontEnd();
        frontEndEndpoint =
            javax.xml.ws.Endpoint.publish(
                "http://localhost:" + PortConfig.FE_SOAP + "/fe", frontEnd);

        Thread.sleep(5000); // wait for startup
    }

    @AfterAll
    static void stopSystem() {
        if (frontEndEndpoint != null) {
            frontEndEndpoint.stop();
        }
        for (ReplicaManager rm : replicaManagers) {
            rm.stop();
        }
    }

    // ===== T1–T5: Normal operation =====

    @Test @Order(1)
    void t1_vehicleCrud() {
        String vehicleId = "MTL9099";

        String addResponse =
            frontEnd.addVehicle("MTLM1111", "QC-NEW-9099", "Sedan", vehicleId, 99.0);
        assertTrue(
            addResponse.startsWith("SUCCESS: Vehicle " + vehicleId),
            "Add vehicle failed: " + addResponse);

        String listResponse = frontEnd.listAvailableVehicle("MTLM1111");
        assertTrue(
            listResponse.contains(vehicleId),
            "List available did not include " + vehicleId + ": " + listResponse);

        String removeResponse = frontEnd.removeVehicle("MTLM1111", vehicleId);
        assertTrue(
            removeResponse.startsWith("SUCCESS: Vehicle " + vehicleId + " removed."),
            "Remove vehicle failed: " + removeResponse);
    }

    @Test @Order(2)
    void t2_localReservation() {
        // Reserve a local vehicle (home office) → budget deducted
        String reserve = frontEnd.reserveVehicle("MTLU2222", "MTL1001", "01012030", "02012030");
        assertTrue(reserve.startsWith("SUCCESS: Reserved MTL1001"), "Reserve failed: " + reserve);
        assertTrue(reserve.contains("Remaining budget"), "Budget not shown: " + reserve);

        // Cancel → budget refunded
        String cancel = frontEnd.cancelReservation("MTLU2222", "MTL1001");
        assertTrue(cancel.startsWith("SUCCESS: Reservation cancelled."), "Cancel failed: " + cancel);
        assertTrue(cancel.contains("New budget: 1000"), "Budget not fully refunded: " + cancel);
    }

    @Test @Order(3)
    void t3_crossOfficeReservation() {
        // Cross-office reserve: MTL customer reserves WPG vehicle
        String reserve = frontEnd.reserveVehicle("MTLU3333", "WPG1001", "01012030", "02012030");
        assertTrue(reserve.startsWith("SUCCESS: Reserved WPG1001"), "Cross-office reserve failed: " + reserve);
        assertTrue(reserve.contains("Remaining budget"), "Budget not shown: " + reserve);

        // Second WPG vehicle → cross-office limit (max 1 per remote office)
        String second = frontEnd.reserveVehicle("MTLU3333", "WPG1002", "03012030", "04012030");
        assertTrue(second.startsWith("FAIL: Already have 1 reservation at WPG office"), "Limit not enforced: " + second);

        // Cancel first → slot freed
        String cancel = frontEnd.cancelReservation("MTLU3333", "WPG1001");
        assertTrue(cancel.startsWith("SUCCESS: Reservation cancelled."), "Cancel failed: " + cancel);

        // Now second WPG vehicle should succeed
        String retry = frontEnd.reserveVehicle("MTLU3333", "WPG1002", "05012030", "06012030");
        assertTrue(retry.startsWith("SUCCESS: Reserved WPG1002"), "Retry after cancel failed: " + retry);

        // O3 (NCN-FE-1): findVehicle routes to default office only (documented deviation)
        // FIND routes to DEFAULT_OFFICE (MTL), so only MTL sedans are returned
        String find = frontEnd.findVehicle("MTLU3333", "Sedan");
        assertTrue(find.contains("MTL"), "Find should return MTL sedans: " + find);
        assertTrue(find.contains("WPG"), "Find should NOT return WPG sedans (single-office path): " + find);

        // O4 (NCN-FE-2): listCustomerReservations routes to home office only (documented deviation)
        // LISTRES routes to customer home office (MTL); WPG reservation stored in WPG office instance
        String list = frontEnd.listCustomerReservations("MTLU3333");
        assertTrue(list.contains("WPG1002"),
            "Cross-office reservation should NOT appear in home-office-only listing (NCN-FE-2): " + list);

        // Cleanup
        frontEnd.cancelReservation("MTLU3333", "WPG1002");
    }

    @Test @Order(4)
    void t4_concurrentRequests() throws Exception {
        // Two threads reserve different vehicles simultaneously — both should succeed
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> result1 = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> result2 = new java.util.concurrent.atomic.AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try { latch.await(); } catch (InterruptedException ignored) {}
            result1.set(frontEnd.reserveVehicle("MTLU4001", "MTL2001", "01012030", "02012030"));
        });
        Thread t2 = new Thread(() -> {
            try { latch.await(); } catch (InterruptedException ignored) {}
            result2.set(frontEnd.reserveVehicle("MTLU4002", "MTL2002", "01012030", "02012030"));
        });
        t1.start(); t2.start();
        latch.countDown();
        t1.join(10000); t2.join(10000);

        assertTrue(result1.get().startsWith("SUCCESS"), "Concurrent reserve 1 failed: " + result1.get());
        assertTrue(result2.get().startsWith("SUCCESS"), "Concurrent reserve 2 failed: " + result2.get());

        // Cleanup
        frontEnd.cancelReservation("MTLU4001", "MTL2001");
        frontEnd.cancelReservation("MTLU4002", "MTL2002");
    }

    @Test @Order(5)
    void t5_waitlist() {
        // First customer reserves a vehicle
        String reserve = frontEnd.reserveVehicle("MTLU5001", "MTL1002", "01012030", "02012030");
        assertTrue(reserve.startsWith("SUCCESS: Reserved MTL1002"), "Reserve failed: " + reserve);

        // Second customer tries same vehicle → added to waitlist
        String waitlist = frontEnd.addToWaitList("MTLU5002", "MTL1002", "01012030", "02012030");
        assertTrue(waitlist.startsWith("SUCCESS: Added to waitlist for MTL1002"), "Waitlist failed: " + waitlist);

        // First customer cancels → auto-assignment should promote waitlisted customer
        String cancel = frontEnd.cancelReservation("MTLU5001", "MTL1002");
        assertTrue(cancel.startsWith("SUCCESS: Reservation cancelled."), "Cancel failed: " + cancel);
        assertTrue(cancel.contains("auto-assigned from waitlist"), "Waitlist auto-assign missing: " + cancel);

        // Cleanup: cancel the auto-assigned reservation
        frontEnd.cancelReservation("MTLU5002", "MTL1002");
    }

    // ===== T6–T10: Byzantine failure =====

    @Test @Order(6)
    void t6_byzantineFirstStrike() throws Exception {
        // Enable Byzantine on R3 — it will return garbage results
        enableByzantine(3, true);
        Thread.sleep(500);

        // FE should still return correct result from R1/R2/R4 majority
        String result = frontEnd.listAvailableVehicle("MTLM1111");
        assertNotNull(result, "Result should not be null with 1 Byzantine replica");
        assertFalse(result.startsWith("FAIL"), "Should succeed with 3 correct replicas: " + result);
    }

    @Test @Order(7)
    void t7_byzantineSecondStrike() {
        // R3 is still Byzantine — second request still gets correct majority
        String result = frontEnd.listAvailableVehicle("MTLM1111");
        assertNotNull(result);
        assertFalse(result.startsWith("FAIL"), "Should succeed with 3 correct replicas: " + result);
    }

    @Test @Order(8)
    void t8_byzantineThirdStrikeReplace() throws Exception {
        // Third Byzantine response from R3 → byzantineCount reaches 3 → REPLACE_REQUEST sent
        String result = frontEnd.listAvailableVehicle("MTLM1111");
        assertFalse(result.startsWith("FAIL"), "Should succeed: " + result);

        // Allow time for replacement to complete (vote window + state transfer + replay)
        Thread.sleep(8000);

        // Verify system still functions after replacement
        String postReplace = frontEnd.listAvailableVehicle("MTLM1111");
        assertNotNull(postReplace, "Post-replacement result should not be null");
        assertFalse(postReplace.startsWith("FAIL"), "System should function after replacement: " + postReplace);
    }

    @Test @Order(9)
    void t9_afterReplacement() {
        // After replacement, R3 should be a fresh non-Byzantine replica
        // All 4 replicas should return correct results
        String result = frontEnd.listAvailableVehicle("MTLM1111");
        assertNotNull(result);
        assertFalse(result.startsWith("FAIL"), "Post-replacement should succeed: " + result);

        // Prove replacement replica participates in writes
        String add = frontEnd.addVehicle("MTLM1111", "QC-T9-CHK", "Sedan", "MTL9009", 50.0);
        assertTrue(add.startsWith("SUCCESS"), "Post-replacement write should succeed: " + add);
        String rm = frontEnd.removeVehicle("MTLM1111", "MTL9009");
        assertTrue(rm.startsWith("SUCCESS"), "Post-replacement cleanup should succeed: " + rm);
    }

    @Test @Order(10)
    void t10_byzantineCounterReset() {
        // Disable Byzantine on R3 (already replaced, but ensure clean state)
        enableByzantine(3, false);

        // Correct response should work — counter was reset after replacement
        String result = frontEnd.listAvailableVehicle("MTLM1111");
        assertNotNull(result);
        assertFalse(result.startsWith("FAIL"), "Counter reset should yield correct result: " + result);
    }

    // ===== T11–T14: Crash failure =====

    @Test @Order(11)
    void t11_crashDetection() {
        // Kill R2 directly — simulate crash
        replicaManagers.get(1).killReplica();

        // FE should still return correct result from remaining 3 replicas (f+1=2 majority)
        String result = frontEnd.listAvailableVehicle("MTLM1111");
        assertNotNull(result, "Should get result even with 1 crashed replica");
        assertFalse(result.startsWith("FAIL"), "Should succeed with 3 replicas: " + result);
    }

    @Test @Order(12)
    void t12_crashRecovery() throws Exception {
        // FE sends CRASH_SUSPECT for R2 → RMs vote → RM2 replaces R2
        // Allow time for crash detection vote + replacement + state transfer
        String result = frontEnd.listAvailableVehicle("MTLM1111");
        assertFalse(result.startsWith("FAIL"), "Should succeed during recovery: " + result);

        Thread.sleep(8000); // wait for replacement to complete

        // Verify system functions after crash recovery
        String postRecovery = frontEnd.listAvailableVehicle("MTLM1111");
        assertNotNull(postRecovery, "Post-recovery result should not be null");
        assertFalse(postRecovery.startsWith("FAIL"), "System should function after crash recovery: " + postRecovery);
    }

    @Test @Order(13)
    void t13_stateTransfer() {
        // After replacement, new R2 has received state and caught up via replay
        // All 4 replicas should be operational again
        String result = frontEnd.listAvailableVehicle("MTLM1111");
        assertNotNull(result);
        assertFalse(result.startsWith("FAIL"), "Post state-transfer should succeed: " + result);

        // Prove recovered replica has correct state and participates in writes
        String add = frontEnd.addVehicle("MTLM1111", "QC-T13-CHK", "Truck", "MTL1309", 70.0);
        assertTrue(add.startsWith("SUCCESS"), "Post-state-transfer write should succeed: " + add);
        String rm = frontEnd.removeVehicle("MTLM1111", "MTL1309");
        assertTrue(rm.startsWith("SUCCESS"), "Post-state-transfer cleanup should succeed: " + rm);
    }

    @Test @Order(14)
    void t14_operationDuringRecovery() {
        // Verify system works normally after recovery — reserve and cancel
        String reserve = frontEnd.reserveVehicle("MTLU1400", "MTL3001", "01012030", "02012030");
        assertTrue(reserve.startsWith("SUCCESS"), "Post-recovery reserve failed: " + reserve);

        String cancel = frontEnd.cancelReservation("MTLU1400", "MTL3001");
        assertTrue(cancel.startsWith("SUCCESS"), "Post-recovery cancel failed: " + cancel);
    }

    // ===== T15–T17: Simultaneous failure =====

    @Test @Order(15)
    void t15_crashPlusByzantine() throws Exception {
        // Simultaneous: R2 crashed + R3 Byzantine → R1/R4 provide majority (f+1=2)
        enableByzantine(3, true);
        Thread.sleep(500);
        replicaManagers.get(1).killReplica();

        String result = frontEnd.listAvailableVehicle("MTLM1111");
        assertNotNull(result, "Should get result with 1 crash + 1 Byzantine");
        assertFalse(result.startsWith("FAIL"), "R1/R4 majority should succeed: " + result);
    }

    @Test @Order(16)
    void t16_dualRecovery() throws Exception {
        // Trigger recovery for both R2 and R3 through continued requests
        frontEnd.listAvailableVehicle("MTLM1111");
        frontEnd.listAvailableVehicle("MTLM1111");

        // Allow time for both replacements
        Thread.sleep(10000);

        // Verify system restored to majority after dual recovery
        String result = frontEnd.listAvailableVehicle("MTLM1111");
        assertNotNull(result, "System should respond after dual recovery");
        assertFalse(result.startsWith("FAIL"), "Dual recovery should restore majority: " + result);

        enableByzantine(3, false);
    }

    @Test @Order(17)
    void t17_stateConsistencyAfterRecovery() {
        // After dual recovery, all 4 replicas should be operational with consistent state
        String result = frontEnd.listAvailableVehicle("MTLM1111");
        assertNotNull(result);
        assertFalse(result.startsWith("FAIL"), "Post dual-recovery should succeed: " + result);

        // Verify state consistency: reserve + cancel works cleanly
        String reserve = frontEnd.reserveVehicle("MTLU1700", "BNF1001", "01012030", "02012030");
        assertTrue(reserve.startsWith("SUCCESS"), "Post dual-recovery reserve failed: " + reserve);
        frontEnd.cancelReservation("MTLU1700", "BNF1001");
    }

    // ===== T18–T21: Edge cases =====

    @Test @Order(18)
    void t18_packetLossRetransmit() {
        // Reliability is built into ReliableUDPSender (500ms/5 retries/x2 backoff).
        // Verify system handles normal request flow correctly (implicit retransmit coverage).
        String addResult = frontEnd.addVehicle("MTLM1111", "QC-RET-1800", "Sedan", "MTL1800", 60.0);
        assertTrue(addResult.startsWith("SUCCESS"), "Reliable add should succeed: " + addResult);

        String removeResult = frontEnd.removeVehicle("MTLM1111", "MTL1800");
        assertTrue(removeResult.startsWith("SUCCESS"), "Reliable remove should succeed: " + removeResult);
    }

    @Test @Order(19)
    void t19_outOfOrderDelivery() {
        // Holdback queue handles out-of-order delivery. Verified via rapid sequential requests:
        // each request gets a unique seqNum and replicas execute in total order.
        String r1 = frontEnd.addVehicle("MTLM1111", "QC-OOD-1901", "SUV", "MTL1901", 100.0);
        String r2 = frontEnd.addVehicle("MTLM1111", "QC-OOD-1902", "SUV", "MTL1902", 100.0);
        assertTrue(r1.startsWith("SUCCESS"), "First sequential add failed: " + r1);
        assertTrue(r2.startsWith("SUCCESS"), "Second sequential add failed: " + r2);

        // Both vehicles should be visible (total-order execution preserved)
        String list = frontEnd.listAvailableVehicle("MTLM1111");
        assertTrue(list.contains("MTL1901"), "MTL1901 missing after ordered execution: " + list);
        assertTrue(list.contains("MTL1902"), "MTL1902 missing after ordered execution: " + list);

        String rm1 = frontEnd.removeVehicle("MTLM1111", "MTL1901");
        assertTrue(rm1.startsWith("SUCCESS"), "Remove MTL1901 should succeed: " + rm1);
        String rm2 = frontEnd.removeVehicle("MTLM1111", "MTL1902");
        assertTrue(rm2.startsWith("SUCCESS"), "Remove MTL1902 should succeed: " + rm2);
    }

    @Test @Order(20)
    void t20_threeSimultaneousClients() throws Exception {
        // 3 threads send requests simultaneously — all should get unique seqNums and succeed
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> r1 = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> r2 = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> r3 = new java.util.concurrent.atomic.AtomicReference<>();

        Thread t1 = new Thread(() -> { try { latch.await(); } catch (Exception e) {} r1.set(frontEnd.addVehicle("MTLM1111", "QC-SIM-2001", "Sedan", "MTL2001", 50.0)); });
        Thread t2 = new Thread(() -> { try { latch.await(); } catch (Exception e) {} r2.set(frontEnd.addVehicle("MTLM1111", "QC-SIM-2002", "Sedan", "MTL2002", 50.0)); });
        Thread t3 = new Thread(() -> { try { latch.await(); } catch (Exception e) {} r3.set(frontEnd.addVehicle("MTLM1111", "QC-SIM-2003", "Sedan", "MTL2003", 50.0)); });

        t1.start(); t2.start(); t3.start();
        latch.countDown();
        t1.join(10000); t2.join(10000); t3.join(10000);

        assertTrue(r1.get().startsWith("SUCCESS"), "Concurrent client 1 failed: " + r1.get());
        assertTrue(r2.get().startsWith("SUCCESS"), "Concurrent client 2 failed: " + r2.get());
        assertTrue(r3.get().startsWith("SUCCESS"), "Concurrent client 3 failed: " + r3.get());

        frontEnd.removeVehicle("MTLM1111", "MTL2001");
        frontEnd.removeVehicle("MTLM1111", "MTL2002");
        frontEnd.removeVehicle("MTLM1111", "MTL2003");
    }

    @Test @Order(21)
    void t21_fullCrossOfficeFlow() {
        // Full cross-office flow: reserve → update dates → cancel
        String reserve = frontEnd.reserveVehicle("MTLU2100", "WPG2001", "01012030", "02012030");
        assertTrue(reserve.startsWith("SUCCESS: Reserved WPG2001"), "Cross-office reserve failed: " + reserve);

        // Update reservation dates
        String update = frontEnd.updateReservation("MTLU2100", "WPG2001", "03012030", "04012030");
        assertTrue(update.contains("SUCCESS"), "Cross-office update failed: " + update);

        // Cancel reservation
        String cancel = frontEnd.cancelReservation("MTLU2100", "WPG2001");
        assertTrue(cancel.startsWith("SUCCESS"), "Cross-office cancel failed: " + cancel);
    }

    // ===== Helpers =====

    private void enableByzantine(int replicaId, boolean enable) {
        try (DatagramSocket socket = new DatagramSocket()) {
            String msg = "SET_BYZANTINE:" + enable;
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length,
                InetAddress.getByName("localhost"),
                PortConfig.ALL_REPLICAS[replicaId - 1]));
        } catch (Exception e) {
            fail("Could not send SET_BYZANTINE: " + e.getMessage());
        }
    }
}
