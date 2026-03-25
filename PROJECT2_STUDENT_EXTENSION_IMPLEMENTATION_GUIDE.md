# PROJECT 2 STUDENT EXTENSION IMPLEMENTATION GUIDE

## Purpose
This guide is for Phase 2 only.
Use it after the shared Phase 1 baseline in `PROJECT2_GROUP_IMPLEMENTATION_GUIDE.md` is complete and approved.
If wording differs, follow `FT-DVRMS_Project2_Design_v3.3_ALIGNMENT_ADDENDUM.md`.

## Entry Condition
- [ ] Group Readiness Checklist in `PROJECT2_GROUP_IMPLEMENTATION_GUIDE.md` is complete.
- [ ] Shared Phase 1 baseline is signed off by [group].
- [ ] Each student has a personal extension branch.
- [ ] Phase 1 deliverables confirmed present: `PortConfig.java`, `UDPMessage.java`, `ReliableUDPSender.java`, `ReplicaLauncher.java`, `FrontEnd.java` (skeleton), `ReplicaManager.java` (skeleton), `Sequencer.java` (skeleton).
- [ ] Phase 1 modifications confirmed: `UDPServer.java` has dedup + ACK, `VehicleReservationWS.java` has holdback queue + snapshot + SET_BYZANTINE, `WaitlistEntry.java` implements `Serializable`.

## Shared Phase 2 Rules
### [group] Shared Rules
- [ ] Owner: [group]
- [ ] Goal: keep Phase 2 extension work aligned and easy to merge
- [ ] Input/Dependency: approved Phase 1 baseline
- [ ] Steps (plain language):
  1. Keep Assignment 3 business rules unchanged.
  2. Avoid non-required framework additions and large refactors.
  3. Keep deterministic behavior and consistent response format across replicas.
  4. Record any assumptions or limitations in Notes.
- [ ] Done Criteria: all extension tasks follow shared constraints and remain merge-ready
- [ ] Notes:

### [group] All Students Replica Modifications (v3.3 + addendum §3.5)
- [ ] Owner: [group]
- [ ] Goal: keep all replica implementations compatible with active replication requirements
- [ ] Input/Dependency: shared Phase 1 architecture and UDP message contract

**Phase 1 already provides (do not rebuild):**

| Feature | Phase 1 Deliverable | Location |
|---|---|---|
| Holdback queue | `PendingExecute` inner class, `handleExecute()` method, `nextExpectedSeq` field | `VehicleReservationWS.java` (Group Guide Step 5) |
| Dedup + ACK | `deliveredMsgIds` set, ACK reply after processing | `UDPServer.java` (Group Guide Step 4) |
| Snapshot | `getStateSnapshot()` and `loadStateSnapshot()` methods | `VehicleReservationWS.java` (Group Guide Step 6) |
| SET_BYZANTINE | `byzantineMode` flag, switch case in `handleUDPRequest()` | `VehicleReservationWS.java` (Group Guide Step 5) |
| Serializable | `WaitlistEntry implements Serializable` | `WaitlistEntry.java` (Group Guide Step 6) |

**Baseline check for `ReplicaLauncher.java`:**
- Confirm the message loop handles `EXECUTE`, `HEARTBEAT_CHECK`, `SET_BYZANTINE`, `STATE_REQUEST`, and `INIT_STATE`.
- If any of these cases are missing in your branch, add only the missing case(s).

**Phase 2 must complete (wire into running system):**

- [ ] Steps (plain language):
  1. Verify `HEARTBEAT_CHECK` → `HEARTBEAT_ACK` exists in `ReplicaLauncher` message loop; add only if missing.
  2. Verify `SET_BYZANTINE` handling is wired to `VehicleReservationWS`; add only if missing.
  3. Verify `STATE_REQUEST` → `STATE_TRANSFER` exists in `ReplicaLauncher`; add only if missing.
  4. Verify `INIT_STATE` loading + ACK exists in `ReplicaLauncher`; add only if missing.
  5. Route incoming `EXECUTE` to the correct office instance (MTL, WPG, BNF) using the vehicle ID or customer ID in the operation string — follow `extractTargetOffice()` pattern in Group Guide Step 7.
  6. Forward `NACK:replicaID:seqStart:seqEnd` returned by `handleExecute()` back to Sequencer.
  7. Keep replica communication on UDP for all server-side flows.
  8. Preserve A3 business logic (vehicle, reservation, waitlist, budget, cross-office rules).

**Build steps — add cases to `ReplicaLauncher.java` message loop:**

```java
// Add after the existing EXECUTE case in the message loop:

case HEARTBEAT_CHECK:
    String hbAck = "HEARTBEAT_ACK:" + replicaId + ":" + mtl.getNextExpectedSeq();
    byte[] hbData = hbAck.getBytes(StandardCharsets.UTF_8);
    socket.send(new DatagramPacket(hbData, hbData.length,
        packet.getAddress(), packet.getPort()));
    break;

case SET_BYZANTINE:
    boolean enable = "true".equalsIgnoreCase(msg.getField(0));
    // Forward to all 3 office instances
    for (VehicleReservationWS office : offices.values()) {
        office.handleUDPRequest("SET_BYZANTINE:" + enable);
    }
    String bzAck = "ACK:SET_BYZANTINE:" + enable;
    socket.send(new DatagramPacket(bzAck.getBytes(StandardCharsets.UTF_8),
        bzAck.length(), packet.getAddress(), packet.getPort()));
    break;

case STATE_REQUEST:
    // Collect snapshot from all 3 offices
    StringBuilder snapshot = new StringBuilder();
    snapshot.append(mtl.getStateSnapshot()).append("|");
    snapshot.append(wpg.getStateSnapshot()).append("|");
    snapshot.append(bnf.getStateSnapshot());
    String stateMsg = "STATE_TRANSFER:" + replicaId + ":" + snapshot.toString();
    byte[] stateData = stateMsg.getBytes(StandardCharsets.UTF_8);
    socket.send(new DatagramPacket(stateData, stateData.length,
        packet.getAddress(), packet.getPort()));
    break;

case INIT_STATE:
    // Load snapshot into all 3 offices
    // Format: INIT_STATE:mtlSnapshot|wpgSnapshot|bnfSnapshot
    String[] snapshots = msg.getField(0).split("\\|");
    mtl.loadStateSnapshot(snapshots[0]);
    wpg.loadStateSnapshot(snapshots[1]);
    bnf.loadStateSnapshot(snapshots[2]);
    // ACK includes lastSeqNum (not nextExpectedSeq) for Sequencer replay baseline.
    // Do not send nextExpectedSeq directly; Sequencer replays from lastSeqNum + 1.
    int lastSeqNum = mtl.getNextExpectedSeq() - 1;
    String initAck = "ACK:INIT_STATE:" + replicaId + ":" + lastSeqNum;
    byte[] initAckData = initAck.getBytes(StandardCharsets.UTF_8);
    socket.send(new DatagramPacket(initAckData,
        initAckData.length, packet.getAddress(), packet.getPort()));
    break;
```

**In the existing `EXECUTE` case**, forward gap reports to Sequencer:

```java
String executeReply = target.handleExecute(seqNum, reqID, feHost, fePort, op.toString());

if (executeReply != null && executeReply.startsWith("NACK:")) {
    byte[] nackData = executeReply.getBytes(StandardCharsets.UTF_8);
    socket.send(new DatagramPacket(nackData, nackData.length,
        packet.getAddress(), packet.getPort())); // back to Sequencer
}
```

**Office routing reference** — `extractTargetOffice()` routes each operation to the correct office instance within the replica. Different operations place the office-identifying ID at different positions:

| Operation | Format | Office from |
|---|---|---|
| RESERVE | `RESERVE:custID:vehID:start:end` | vehID (parts[2]) |
| CANCEL | `CANCEL:custID:vehID` | vehID (parts[2]) |
| WAITLIST | `WAITLIST:custID:vehID:start:end` | vehID (parts[2]) |
| ATOMIC_UPDATE | `ATOMIC_UPDATE:custID:vehID:start:end` | vehID (parts[2]) |
| ADDVEHICLE | `ADDVEHICLE:managerID:...` | managerID (parts[1]) |
| REMOVEVEHICLE | `REMOVEVEHICLE:managerID:...` | managerID (parts[1]) |
| LISTAVAILABLE | `LISTAVAILABLE:managerID` | managerID (parts[1]) |
| FIND | `FIND:vehicleType` | deterministic aggregator office (e.g., MTL) |
| LISTRES | `LISTRES:custID` | custID (parts[1]) |

```java
private static String extractTargetOffice(String operation) {
    String[] parts = operation.split(":", -1);
    String op = parts[0];
    switch (op) {
        case "FIND":
            // deterministic aggregator office for merged cross-office find response
            return "MTL";
        case "LISTRES":
            return ServerIdRules.extractOfficeID(parts[1]); // customerID → office
        case "ADDVEHICLE":
        case "REMOVEVEHICLE":
        case "LISTAVAILABLE":
            return ServerIdRules.extractOfficeID(parts[1]); // managerID → office
        default:
            // RESERVE, CANCEL, WAITLIST, ATOMIC_UPDATE — vehicleID is at parts[2]
            if (parts.length >= 3) {
                return ServerIdRules.extractOfficeID(parts[2]);
            }
            return "MTL"; // fallback
    }
}
```

> **Note:** `FIND` uses a deterministic aggregator office (for example `MTL`) so each `EXECUTE` is processed exactly once while still returning merged cross-office results through existing A3 logic.

- [ ] Done Criteria: each replica variant stays deterministic and compatible with FE, Sequencer, and RM flows
- [ ] Notes: `UDPServer.java` dedup+ACK requires no further Phase 2 changes. If RMs are running, do not launch `ReplicaLauncher` manually in the same run.

---

## Phase 2 Student Extension Blocks

### [Student 1] Front End Extension (v3.3 + addendum §3.1)
- [ ] Owner: [Student 1]
- [ ] Goal: complete FE voting and failure-notification behavior for active replication
- [ ] Input/Dependency: Sequencer request path and replica result messages are available

**Builds on Phase 1 scaffolding in `FrontEnd.java`:**
- `RequestContext` inner class — tracks per-request results and timing
- `forwardAndCollect()` — sends REQUEST to Sequencer, waits for results
- `vote()` — majority voting skeleton
- `listenForResults()` — UDP listener for RESULT messages from replicas
- `sendToAllRMs()` — broadcasts failure notifications to all RMs

#### Step 1 — Publish FE SOAP endpoint

Phase 1 `FrontEnd.java` has `@WebService` but no `main()` or `Endpoint.publish()`. Add:

```java
public static void main(String[] args) {
    FrontEnd fe = new FrontEnd();
    javax.xml.ws.Endpoint.publish(
        "http://localhost:" + PortConfig.FE_SOAP + "/fe", fe);
    System.out.println("FrontEnd SOAP endpoint published at http://localhost:8080/fe");
}
```

Implement all `@WebMethod` signatures matching `VehicleReservationWS`: addVehicle, removeVehicle, listAvailableVehicle, reserveVehicle, updateReservation, cancelReservation, findVehicle, listCustomerReservations, addToWaitList. Each method calls `forwardAndCollect(operation)` with the appropriate operation string.

Client connects using the same pattern as A3:
```java
URL wsdlUrl = new URL("http://localhost:8080/fe?wsdl");
```

#### Step 2 — Verify Phase 1 RESULT and voting are correct

Phase 1 already provides the following in the group guide (verify these are present before proceeding):

- `executeAndDeliver()` in `VehicleReservationWS.java` sends `RESULT:seqNum:reqID:replicaID:resultString` (group guide line 426) — includes reqID for FE matching.
- `sendResultToFE()` is defined in `VehicleReservationWS.java` (group guide lines 432–442).
- `listenForResults()` in `FrontEnd.java` parses `RESULT:seqNum:reqID:replicaID:resultString` (group guide lines 845–851).
- `RequestContext` uses `CompletableFuture` that releases on 2 identical results (group guide lines 675–698) — not `CountDownLatch`.
- `forwardAndCollect()` uses `ctx.awaitMajority(timeout)` (group guide lines 724–774).

**If any of the above are missing from your Phase 1 code, add them from the group guide before continuing.**

#### Step 4 — Complete voting and failure reporting

```java
private void reportDissenters(RequestContext ctx, String majorityResult) {
    for (var entry : ctx.replicaResults.entrySet()) {
        String replicaID = entry.getKey();
        if (!entry.getValue().equals(majorityResult)) {
            // Incorrect result — report to all RMs (§4.4: reqID:seqNum:replicaID)
            sendToAllRMs("INCORRECT_RESULT:" + ctx.requestID + ":" + ctx.seqNum + ":" + replicaID);
            int count = byzantineCount
                .computeIfAbsent(replicaID, k -> new AtomicInteger(0))
                .incrementAndGet();
            if (count >= 3) {
                sendToAllRMs("REPLACE_REQUEST:" + replicaID + ":BYZANTINE_THRESHOLD");
            }
        } else {
            // Correct — reset counter
            byzantineCount.computeIfAbsent(replicaID, k -> new AtomicInteger(0)).set(0);
        }
    }

    // Report crash for non-responding replicas
    for (int i = 0; i < PortConfig.ALL_REPLICAS.length; i++) {
        String rid = String.valueOf(i + 1);
        if (!ctx.replicaResults.containsKey(rid)) {
            sendToAllRMs("CRASH_SUSPECT:" + ctx.requestID + ":" + ctx.seqNum + ":" + rid);
        }
    }
}

private void updateSlowTime(RequestContext ctx) {
    long elapsed = System.currentTimeMillis() - ctx.sentTime;
    slowestResponseTime.updateAndGet(prev -> Math.max(prev, elapsed));
}
```

- [ ] Steps summary:
  1. Publish FE SOAP endpoint at `http://localhost:8080/fe`.
  2. Verify Phase 1 RESULT format includes `reqID` and voting uses `CompletableFuture` (not `CountDownLatch`).
  3. Complete `reportDissenters()` — send `INCORRECT_RESULT:reqID:seqNum:replicaID` to all RMs for mismatching replicas (§4.4).
  4. Track consecutive mismatches per replica. Send `REPLACE_REQUEST` at threshold 3.
  5. Send `CRASH_SUSPECT:reqID:seqNum:replicaID` for non-responding replicas after timeout (§4.4).
  6. Return majority result to SOAP client as soon as 2 identical results confirmed (f+1 = 2).
- [ ] Done Criteria: FE returns correct result quickly and emits required RM notifications for Byzantine/crash handling
- [ ] Notes: FE processes one client request at a time per the design doc Phase 1 constraint. `slowestResponseTime` updates adaptively after each request.

---

### [Student 2] Replica Manager (RM) Extension (v3.3 + addendum §3.2)
- [ ] Owner: [Student 2]
- [ ] Goal: complete RM failure detection, consensus, replacement, and recovery
- [ ] Input/Dependency: FE notifications and replica heartbeat/state endpoints are available

**Builds on Phase 1 scaffolding in `ReplicaManager.java`:**
- `launchReplica()` / `killReplica()` — start/stop replica OS process via `ReplicaLauncher`
- `heartbeatLoop()` / `sendHeartbeat()` — periodic `HEARTBEAT_CHECK` to replica port
- `handleByzantineReplace()` — broadcasts `VOTE_BYZANTINE` (stub — needs vote collection)
- `handleCrashSuspect()` — verifies via heartbeat, broadcasts vote (stub — needs tally)
- `handleVote()` — stub — needs vote collection and majority decision
- `replaceReplica()` — stub — needs state transfer implementation
- `handleStateRequest()` — stub — needs snapshot request/forward

#### Step 1 — Complete `handleVote()` with vote collection

Phase 1 stub has no vote tallying. Add a vote collection window:

```java
// Add field to ReplicaManager:
private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> voteCollector =
    new ConcurrentHashMap<>(); // voteKey → (rmId → vote)
private final ConcurrentHashMap<String, Long> voteWindowStart = new ConcurrentHashMap<>();
private static final long VOTE_WINDOW_MS = 2000; // evaluate reachable votes within timeout

private void handleVote(UDPMessage msg, DatagramSocket socket) {
    // VOTE_BYZANTINE:<targetId>:<voterId>
    // VOTE_CRASH:<targetId>:<decision>:<voterId>
    if (msg.fieldCount() < 2) {
        return; // malformed vote packet
    }
    String voteType = msg.getType().name();
    String targetId = msg.getField(0);
    String voteKey = voteType + ":" + targetId;

    String voterDecision;
    String voterId;
    if (msg.getType() == UDPMessage.Type.VOTE_CRASH) {
        if (msg.fieldCount() < 3) {
            return; // malformed vote packet
        }
        voterDecision = msg.getField(1);  // ALIVE or CRASH_CONFIRMED
        voterId = msg.getField(2);         // sender RM id
    } else {
        voterDecision = "AGREE";           // Byzantine votes are implicit agree
        voterId = msg.getField(1);         // sender RM id
    }

    // Record this vote keyed by the sender's RM identity
    voteCollector.computeIfAbsent(voteKey, k -> new ConcurrentHashMap<>())
        .put("RM" + voterId, voterDecision);
    voteWindowStart.putIfAbsent(voteKey, System.currentTimeMillis());

    // Evaluate only after vote timeout; totalVotes = reachable votes in window
    Long windowStart = voteWindowStart.get(voteKey);
    if (windowStart == null || System.currentTimeMillis() - windowStart < VOTE_WINDOW_MS) {
        return;
    }

    ConcurrentHashMap<String, String> votes = voteCollector.get(voteKey);
    if (votes != null) {
        int totalVotes = votes.size();
        if (totalVotes == 0) return;

        long agreeCount = votes.values().stream()
            .filter(v -> v.equals("AGREE") || v.equals("CRASH_CONFIRMED")).count();
        // Strict majority of reachable RMs in the vote window
        if (agreeCount > totalVotes / 2) {
            // Only the RM responsible for the targetId should perform replacement
            if (targetId.equals(String.valueOf(replicaId))) {
                replaceReplica();
            }
        }
        voteCollector.remove(voteKey);
        voteWindowStart.remove(voteKey);
    }
}
```

#### Step 2 — Complete `handleCrashSuspect()` with own verification

```java
private void handleCrashSuspect(UDPMessage msg, DatagramSocket socket) {
    if (msg.fieldCount() < 3) {
        return; // malformed CRASH_SUSPECT
    }
    String suspectedId = msg.getField(2); // CRASH_SUSPECT:reqID:seqNum:suspectedID (§4.4)
    // Heartbeat the suspected replica's port, not our own
    int suspectedPort = PortConfig.ALL_REPLICAS[Integer.parseInt(suspectedId) - 1];
    boolean alive = sendHeartbeatTo(suspectedPort);
    // Broadcast vote to all RMs (includes voter ID for correct tallying)
    String vote = alive
        ? "VOTE_CRASH:" + suspectedId + ":ALIVE:" + replicaId
        : "VOTE_CRASH:" + suspectedId + ":CRASH_CONFIRMED:" + replicaId;
    byte[] voteData = vote.getBytes(StandardCharsets.UTF_8);
    for (int rmPort : PortConfig.ALL_RMS) {
        try {
            socket.send(new DatagramPacket(voteData, voteData.length,
                InetAddress.getByName("localhost"), rmPort));
        } catch (Exception e) {
            System.err.println("RM" + replicaId + ": vote send error: " + e.getMessage());
        }
    }
}
```

#### Step 3 — Complete `replaceReplica()` with state transfer

Phase 1 stub has only comments. Implement full replacement + state transfer flow:

```java
private void replaceReplica() {
    System.out.println("RM" + replicaId + ": Starting replica replacement");

    // Step 1: Kill faulty replica (Byzantine) or skip (crash — already dead)
    killReplica();

    // Step 2: Launch fresh replica
    launchReplica();

    // Step 3: Request state from lowest-ID healthy replica
    String snapshot = requestStateFromHealthyReplica();
    int lastSeqNum = -1; // default: no state → Sequencer replays everything

    if (snapshot != null) {
        // Step 4: Send state to new replica
        try (DatagramSocket socket = new DatagramSocket()) {
            String initMsg = "INIT_STATE:" + snapshot;
            byte[] data = initMsg.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length,
                InetAddress.getByName("localhost"), replicaPort));

            // Wait for ACK — format: ACK:INIT_STATE:replicaId:lastSeqNum
            socket.setSoTimeout(5000);
            byte[] buf = new byte[8192];
            DatagramPacket ack = new DatagramPacket(buf, buf.length);
            socket.receive(ack);
            String ackStr = new String(ack.getData(), 0, ack.getLength(),
                StandardCharsets.UTF_8);
            // Extract lastSeqNum from ACK (set by loadStateSnapshot)
            String[] ackParts = ackStr.split(":");
            if (ackParts.length >= 4) {
                lastSeqNum = Integer.parseInt(ackParts[3]);
            }
            System.out.println("RM" + replicaId + ": State transfer complete, lastSeq=" + lastSeqNum);
        } catch (Exception e) {
            System.err.println("RM" + replicaId + ": State transfer failed: " + e.getMessage());
        }
    }

    // Step 5: Notify Sequencer + FE that replica is ready
    // Include lastSeqNum so Sequencer can replay from lastSeqNum+1 (§3.2)
    // Use plain UDP — Sequencer/FE/RMs do not send ACK for REPLICA_READY
    try (DatagramSocket socket = new DatagramSocket()) {
        String readyMsg = "REPLICA_READY:" + replicaId + ":localhost:" + replicaPort
            + ":" + lastSeqNum;
        byte[] readyData = readyMsg.getBytes(StandardCharsets.UTF_8);
        InetAddress localhost = InetAddress.getByName("localhost");
        socket.send(new DatagramPacket(readyData, readyData.length,
            localhost, PortConfig.SEQUENCER));
        socket.send(new DatagramPacket(readyData, readyData.length,
            localhost, PortConfig.FE_UDP));
        for (int rmPort : PortConfig.ALL_RMS) {
            socket.send(new DatagramPacket(readyData, readyData.length,
                localhost, rmPort));
        }
    } catch (Exception e) {
        System.err.println("RM" + replicaId + ": REPLICA_READY send failed: " + e.getMessage());
    }
}
```

#### Step 4 — Complete `handleStateRequest()` and helper

```java
private void handleStateRequest(UDPMessage msg, DatagramSocket socket, DatagramPacket from) {
    // Another RM is asking for a state snapshot from our healthy replica
    try (DatagramSocket reqSocket = new DatagramSocket()) {
        String stateReq = "STATE_REQUEST:" + replicaId;
        byte[] data = stateReq.getBytes(StandardCharsets.UTF_8);
        reqSocket.send(new DatagramPacket(data, data.length,
            InetAddress.getByName("localhost"), replicaPort));

        // Wait for STATE_TRANSFER response from replica
        reqSocket.setSoTimeout(10000);
        byte[] buf = new byte[65535]; // snapshots can be large
        DatagramPacket response = new DatagramPacket(buf, buf.length);
        reqSocket.receive(response);
        String stateResponse = new String(response.getData(), 0, response.getLength(),
            StandardCharsets.UTF_8);

        // Forward to requesting RM
        byte[] forwardData = stateResponse.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(forwardData, forwardData.length,
            from.getAddress(), from.getPort()));
    } catch (Exception e) {
        System.err.println("RM" + replicaId + ": State request handling failed: " + e.getMessage());
    }
}

private String requestStateFromHealthyReplica() {
    // Request state from lowest-ID healthy RM (skip self)
    // Uses plain UDP — the try-each-RM loop is the retry mechanism
    for (int i = 0; i < PortConfig.ALL_RMS.length; i++) {
        int targetRmPort = PortConfig.ALL_RMS[i];
        if (targetRmPort == rmPort) continue; // skip self

        try (DatagramSocket socket = new DatagramSocket()) {
            String req = "STATE_REQUEST:" + replicaId;
            byte[] reqData = req.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(reqData, reqData.length,
                InetAddress.getByName("localhost"), targetRmPort));

            socket.setSoTimeout(10000);
            byte[] buf = new byte[65535];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            socket.receive(response);
            String raw = new String(response.getData(), 0, response.getLength(),
                StandardCharsets.UTF_8);

            if (raw.startsWith("STATE_TRANSFER:")) {
                // STATE_TRANSFER:sourceReplicaId:mtlSnap|wpgSnap|bnfSnap
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
```

- [ ] Steps summary:
  1. Monitor co-located replica health via `heartbeatLoop()` (3s interval, 2s timeout).
  2. Listen on RM port (7001–7004) for FE notifications and RM votes. Keep per-packet parse/dispatch in `try/catch` so malformed UDP packets are logged and ignored.
  3. On `CRASH_SUSPECT`: validate required fields first, then verify by heartbeating the suspected replica and broadcast `VOTE_CRASH` to all RMs.
  4. On `REPLACE_REQUEST`: validate required fields first, then broadcast `VOTE_BYZANTINE` to all RMs.
  5. `handleVote()`: collect votes, require strict majority of reachable RMs.
  6. On approved replacement: `killReplica()` (Byzantine) or skip (crash), then `launchReplica()`.
  7. State transfer: `STATE_REQUEST` → healthy RM → snapshot → `INIT_STATE` to new replica.
  8. Send `REPLICA_READY:replicaID:host:port:lastSeqNum` to Sequencer for replay catch-up.
  9. Broadcast `REPLICA_READY` to FE and other RMs.
- [ ] Done Criteria: RM can replace faulty/crashed replica and restore consistent replica state
- [ ] Notes: Vote decision is based on strict majority of reachable votes within the vote timeout window. Example: 2/3 reachable votes is sufficient.

---

### [Student 3] Sequencer Extension (v3.3 + addendum §3.3, §4.1)
- [ ] Owner: [Student 3]
- [ ] Goal: complete total-order and reliable multicast behavior
- [ ] Input/Dependency: FE request format and replica ACK contract are stable

**Builds on Phase 1 scaffolding in `Sequencer.java`:**
- `handleRequest()` — assigns `seqNum` from `sequenceCounter`, builds EXECUTE message
- `multicast()` — sends to all replicas using `ReliableUDPSender` in separate threads
- `handleAck()` — records ACK (stub — needs replica identification)
- `handleNack()` — replays missing range from `historyBuffer`
- `handleReplicaReady()` — stub — needs replay to single recovered replica
- `historyBuffer` — `ConcurrentHashMap<Integer, String>` storing all EXECUTE messages
- `ackTracker` — `ConcurrentHashMap<Integer, Set<Integer>>` per-message ACK tracking

> **Threading warning:** `ReliableUDPSender.send()` blocks for up to `500 + 1000 + 2000 + 4000 + 8000 = 15,500ms` on max retries. If `multicast()` runs synchronously in the main receive loop, the Sequencer cannot process new REQUEST or NACK messages for up to 15.5s per unresponsive replica. Phase 1 already uses `new Thread(...)` per replica in `multicast()` — keep this pattern.
>
> **Socket warning:** Phase 1's `multicast()` passes the main listener socket to sender threads. This is not thread-safe — `setSoTimeout()` and `receive()` on a shared socket cause ACK cross-talk between threads. Each sender thread must create its own `DatagramSocket`.

#### Step 1 — Complete `handleAck()` with replica identification

Phase 1 stub records nothing. Identify the ACKing replica from the source address:

```java
private void handleAck(UDPMessage msg, DatagramPacket from) {
    int seqNum = Integer.parseInt(msg.getField(0));
    var acks = ackTracker.get(seqNum);
    if (acks != null) {
        // Identify replica by source port
        int replicaPort = from.getPort();
        acks.add(replicaPort);

        // Optional: if all 4 replicas ACKed, clean up tracker
        if (acks.size() >= PortConfig.ALL_REPLICAS.length) {
            ackTracker.remove(seqNum);
        }
    }
}
```

#### Step 2 — Verify Phase 1 `handleNack()` targets requesting replica only

Phase 1 already provides `handleNack()` that replays to the requesting replica only (group guide lines 1255–1278) using `from.getAddress()` and `from.getPort()`. Each replay thread creates its own `DatagramSocket`. Verify this is present in your Phase 1 code before proceeding.

#### Step 3 — Complete `handleReplicaReady()` with targeted replay

Phase 1 is a stub with TODO. Implement replay to the single recovered replica:

```java
private void handleReplicaReady(UDPMessage msg, DatagramSocket socket) {
    // REPLICA_READY:replicaID:host:port:lastSeqNum
    String replicaID = msg.getField(0);
    String address = msg.getField(1);
    int replicaPort = Integer.parseInt(msg.getField(2));
    int lastSeq = Integer.parseInt(msg.getField(3));

    System.out.println("Sequencer: " + replicaID + " ready, replaying from seq "
        + (lastSeq + 1));

    // Replay all messages after lastSeq to the recovered replica only
    // Each thread creates its own socket — sharing the main socket is not thread-safe
    int current = sequenceCounter.get();
    try {
        InetAddress addr = InetAddress.getByName(address);
        for (int seq = lastSeq + 1; seq < current; seq++) {
            String historicMsg = historyBuffer.get(seq);
            if (historicMsg != null) {
                final String msgToSend = historicMsg;
                new Thread(() -> {
                    try (DatagramSocket sendSocket = new DatagramSocket()) {
                        sender.send(msgToSend, addr, replicaPort, sendSocket);
                    } catch (Exception e2) {
                        System.err.println("Sequencer: replay send failed: " + e2.getMessage());
                    }
                }).start();
            }
        }
    } catch (Exception e) {
        System.err.println("Sequencer: replay to " + replicaID + " failed: " + e.getMessage());
    }

    // Update replica address list if needed
    try {
        InetSocketAddress newAddr = new InetSocketAddress(
            InetAddress.getByName(address), replicaPort);
        // Replace old address for this replica
        replicaAddresses.removeIf(a -> a.getPort() == replicaPort);
        replicaAddresses.add(newAddr);
    } catch (Exception e) { /* log */ }
}
```

#### Step 4 — Update main loop to pass `DatagramPacket` to handlers

The main loop needs to pass the source packet so handlers can identify the sender:

```java
// In start() — update switch to pass packet:
switch (msg.getType()) {
    case REQUEST:
        handleRequest(msg, socket);
        break;
    case ACK:
        handleAck(msg, packet);          // pass packet for sender identification
        break;
    case NACK:
        handleNack(msg, socket, packet); // pass packet for targeted replay
        break;
    case REPLICA_READY:
        handleReplicaReady(msg, socket);
        break;
    default:
        break;
}
```

- [ ] Steps summary:
  1. Receive `REQUEST` from FE on port 9100, assign `seqNum`, build `EXECUTE`, store in `historyBuffer`.
  2. Multicast to all replica ports in separate threads (never block main loop).
  3. Complete `handleAck()`: identify replica by source port, add to `ackTracker`.
  4. Verify Phase 1 `handleNack()` replays to requesting replica only (not all replicas).
  5. Complete `handleReplicaReady()`: parse `REPLICA_READY:replicaID:host:port:lastSeqNum`, replay from `lastSeqNum+1` to recovered replica only.
  6. On ACK timeout after all retries: mark replica as unresponsive, notify RM path.
  7. Handle duplicate ACK/retransmit idempotently.
- [ ] Done Criteria: replicas receive deterministic total order with practical UDP reliability support
- [ ] Notes: Sequencer is assumed failure-free (per project spec). It is not replicated. `historyBuffer` is unbounded — acceptable for assignment scope.

---

### [Student 4] Test Cases and TestClient Extension (v3.3 + addendum §3.4, §5.1–5.5)
- [ ] Owner: [Student 4]
- [ ] Goal: provide executable test coverage for normal flow and all required failure scenarios
- [ ] Input/Dependency: FE, Sequencer, RM, and replicas are runnable in integrated environment

**Builds on Phase 1 scaffolding in `ReplicationIntegrationTest.java`:**
- `@BeforeAll startSystem()` — starts Sequencer, 4 ReplicaManagers (which launch replicas), and FE as in-process threads. Publishes FE SOAP endpoint. Waits 3s for startup.
- `@AfterAll stopSystem()` — shuts down all components.
- 21 test method stubs: `t1_vehicleCrud()` through `t21_fullCrossOfficeFlow()`.
- `enableByzantine(int replicaId, boolean enable)` — sends `SET_BYZANTINE:true/false` via UDP to replica port.

#### Step 1 — Fix startup for crash testing

Phase 1 starts replicas via `ReplicaLauncher.main()` as threads. For crash testing (T11–T14), replicas must be separate OS processes so `destroyForcibly()` actually kills them. Use `ReplicaManager` (which launches replicas as OS processes) instead:

```java
private static Process[] replicaProcesses = new Process[4];

@BeforeAll
static void startSystem() throws Exception {
    // 1. Start Sequencer first (port 9100)
    new Thread(() -> new Sequencer().start()).start();
    Thread.sleep(500);

    // 2. Start 4 RMs (which launch replicas as OS processes)
    for (int i = 1; i <= 4; i++) {
        final int id = i;
        new Thread(() -> new ReplicaManager(id).start()).start();
    }
    Thread.sleep(2000); // wait for replicas to start

    // 3. Start FE (port 8080 SOAP + port 9000 UDP) — must be last
    FrontEnd fe = new FrontEnd();
    javax.xml.ws.Endpoint.publish(
        "http://localhost:" + PortConfig.FE_SOAP + "/fe", fe);

    Thread.sleep(1000); // final stabilization

    // 4. Connect SOAP client
    // feService = ... (connect to http://localhost:8080/fe?wsdl)
}
```

**Startup order matters:**
1. Sequencer first (port 9100) — must be ready before FE sends REQUEST.
2. RMs next (ports 7001–7004) — each RM launches and monitors its replica (ports 6001–6004).
3. FE last (port 8080 SOAP + port 9000 UDP) — publishes client endpoint after backend is ready.
4. Do **not** launch `ReplicaLauncher` separately when RMs are running.

#### Step 2 — Add crash simulation helper

```java
private void crashReplica(int replicaId) {
    // Send kill signal or directly destroy the process
    // RMs detect via heartbeat failure
    try (DatagramSocket socket = new DatagramSocket()) {
        String msg = "SHUTDOWN:" + replicaId;
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(data, data.length,
            InetAddress.getByName("localhost"),
            PortConfig.ALL_REPLICAS[replicaId - 1]));
    } catch (Exception e) {
        // If UDP shutdown doesn't work, the RM's heartbeat will detect the crash
    }
}
```

#### Step 3 — Byzantine simulation helper (from Phase 1)

```java
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
```

#### Step 4 — Implement T1–T21

- [ ] Steps (plain language):
  1. Start all components following the startup order above.
  2. Connect SOAP client to FE at `http://localhost:8080/fe`.
  3. **T1–T5 Normal operation:**
     - T1: Add, list, remove vehicle through FE — verify consistent result.
     - T2: Reserve a vehicle, verify budget deducted, verify via `listCustomerReservations`.
     - T3: Cross-office reservation (e.g., MTLU1111 reserves WPG1001).
     - T4: Two threads reserve same vehicle simultaneously — one succeeds, one fails, deterministic across replicas.
     - T5: Second customer tries same vehicle → waitlisted.
  4. **T6–T10 Byzantine failure:**
     - T6: `enableByzantine(3, true)` → send request → FE returns correct from R1/R2/R4.
     - T7: R3 still Byzantine → `byzantineCount` = 2.
     - T8: Third strike → `byzantineCount` = 3 → triggers `REPLACE_REQUEST` → RM replaces R3.
     - T9: New R3 returns correct results after replacement.
     - T10: `enableByzantine(3, false)` → correct response resets counter to 0.
  5. **T11–T14 Crash failure:**
     - T11: `crashReplica(2)` → FE returns from 3 matching replicas.
     - T12: RMs detect crash via heartbeat + FE `CRASH_SUSPECT` → vote → replace R2.
     - T13: New R2 gets state via snapshot transfer → subsequent requests match all 4.
     - T14: System works with 3 replicas while R2 recovers.
  6. **T15–T17 Simultaneous failure:**
     - T15: `crashReplica(2)` + `enableByzantine(3, true)` → R1/R4 match (f+1 = 2 still sufficient).
     - T16: Both R2 and R3 replaced via RM consensus.
     - T17: All 4 replicas have identical state after dual recovery.
  7. **T18–T21 Edge/reliability:**
     - T18: Simulate packet loss → Sequencer retransmits → replica eventually ACKs.
     - T19: Out-of-order delivery (seq#5 before seq#4) → holdback queue buffers → correct order.
     - T20: 3 threads send requests simultaneously → unique seq# each → same total order on all replicas.
     - T21: Full cross-office flow: reserve → update → cancel across offices.
  8. Collect pass/fail evidence with clear logs for each scenario.
- [ ] Done Criteria: T1–T21 have executable steps, expected result checks, and recorded outcomes. Group base should pass at least T1–T5 before individual extensions are complete.
- [ ] Notes: T6–T21 may only fully pass after all role extensions (FE voting, RM replacement, Sequencer replay) are complete.

---

## Cross-Student Handoff Checklist
### [group] Integration Handoff
- [ ] Owner: [group]
- [ ] Goal: ensure all student extensions are ready for merge and demo validation
- [ ] Input/Dependency: each student block above is marked complete
- [ ] Steps (plain language):
  1. [Student 1] shares final FE request/response and notification behavior. Confirms `seqNum → reqID` mapping works via RESULT message format change. Confirms `CompletableFuture`-based voting returns correct majority.
  2. [Student 2] shares RM vote/replacement/state-transfer behavior and limits. Confirms snapshot transfer restores full state for all 3 offices including `nextExpectedSeq`.
  3. [Student 3] shares Sequencer ordering/retransmission/replay behavior. Confirms `handleNack()` replays to requesting replica only. Confirms `handleReplicaReady()` replays to single recovered replica.
  4. [Student 4] shares TestClient commands and T1-T21 evidence summary. Confirms startup uses OS processes via RM for crash simulation.
  5. Run integrated regression check and note blockers before merge.
- [ ] Done Criteria: extension branches are integration-ready with clear responsibilities and evidence
- [ ] Notes:

## Public APIs, Interfaces, and Types (Phase 2 Changes)
- `REPLICA_READY` message format: `REPLICA_READY:replicaID:host:port:lastSeqNum` — includes lastSeqNum so Sequencer can replay from the correct point.
- `VOTE_BYZANTINE` message format: `VOTE_BYZANTINE:targetId:voterId` — targetId is the faulty replica, voterId is the sending RM.
- `VOTE_CRASH` message format: `VOTE_CRASH:suspectedId:verdict:voterId` — suspectedId first, then verdict (ALIVE or CRASH_CONFIRMED), then voter RM id.
- `INIT_STATE` ACK format: `ACK:INIT_STATE:replicaId:lastSeqNum` — `lastSeqNum` means highest sequence already applied (`nextExpectedSeq - 1`). Do not send `nextExpectedSeq` directly.
- Sequencer replay rule: on `REPLICA_READY`, replay starts from `lastSeqNum + 1` for that recovered replica only.
- `handleVote()` decision rule: strict majority of reachable votes collected within a bounded vote timeout window.
- Phase 1 already provides: RESULT with reqID, CompletableFuture voting, sendResultToFE(), targeted NACK replay, per-thread sockets. Verify these are present before extending.

## Guide Self-Check
- [ ] Only these placeholders are used: [Student 1], [Student 2], [Student 3], [Student 4], [group]
- [ ] Phase separation is explicit: this file is Phase 2 companion, group guide remains Phase 1 baseline
- [ ] Each extension block references Phase 1 scaffolding it builds on
- [ ] Each extension block includes code snippets showing what to build
- [ ] Phase 1 vs. Phase 2 responsibilities are split in the shared replica block
- [ ] Phase 1 deliverables verified (RESULT format, CompletableFuture voting, sendResultToFE, targeted NACK replay, per-thread sockets)
- [ ] Canonical numeric `replicaID` (`1..4`) is used consistently in FE/RM/Sequencer message examples
- [ ] Replica `EXECUTE` path explicitly forwards `NACK` to Sequencer
- [ ] `extractTargetOffice()` mapping includes manager operations (`ADDVEHICLE`, `REMOVEVEHICLE`, `LISTAVAILABLE`)
- [ ] RM vote rule is documented as strict majority of reachable votes within vote timeout
- [ ] Traceability is clear:
  - [ ] [Student 1] section maps to v3.3 + addendum §3.1
  - [ ] [Student 2] section maps to v3.3 + addendum §3.2
  - [ ] [Student 3] section maps to v3.3 + addendum §3.3 and §4.1
  - [ ] [Student 4] section maps to v3.3 + addendum §3.4 and §5.1–5.5
  - [ ] [group] replica block maps to v3.3 + addendum §3.5
- [ ] Language stays short, direct, and non-over-engineered
