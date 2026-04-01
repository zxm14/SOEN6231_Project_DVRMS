# FT-DVRMS Demo Guide

COMP 6231 — Distributed System Design, Winter 2026, Concordia University

---

## 1. System Overview

FT-DVRMS is a fault-tolerant distributed vehicle reservation system (3 offices: MTL, WPG, BNF) built on **active replication** with **4 identical replicas**. All replicas execute operations in the same total order (state machine replication). The system tolerates **1 Byzantine fault + 1 crash fault simultaneously**: with 4 replicas, worst case leaves 2 correct replicas always returning identical results (`f+1 = 2` matching threshold). Clients interact via a single SOAP endpoint — replication is fully transparent.

---

## 2. Architecture

```
 ┌─────────────────────────────────────┐
 │         Clients (SOAP/HTTP)         │
 │    ManagerClient / CustomerClient   │
 └────────────────┬────────────────────┘
                  │ SOAP :8080
                  ▼
 ┌─────────────────────────────────────┐
 │           Front End (FE)            │
 │   SOAP :8080 (in) / UDP :9000 (in)  │
 │   Voting: 2 matching = correct      │
 └────────────────┬────────────────────┘
                  │ REQUEST (UDP :9100)
                  ▼
 ┌─────────────────────────────────────┐
 │            Sequencer                │
 │          UDP :9100                  │
 │   Assigns seqNum, multicasts EXECUTE│
 └──┬──────┬─────┬──────┬─────────────┘
    │      │     │      │  EXECUTE (UDP)
    ▼      ▼     ▼      ▼
  R1:6001 R2:6002 R3:6003 R4:6004   ← 4 Replicas
    │      │     │      │  RESULT → FE :9000
    │      │     │      │
  RM1:7001 RM2:7002 RM3:7003 RM4:7004  ← Replica Managers
  (supervise, consensus, replace)
```

---

## 3. Component Details

### Front End (FE)
- Exposes single SOAP endpoint at `:8080` — clients see one server (replication transparency)
- Generates unique `REQ-N` IDs; forwards each request to Sequencer via `ReliableUDPSender`
- Listens for `RESULT` messages on UDP `:9000` from all 4 replicas
- **Majority voting:** returns as soon as **2 results match** (`f+1=2`) — no need to wait for all 4
- Tracks per-replica `byzantineCount`; on **3rd mismatch** sends `REPLACE_REQUEST` to all RMs
- On timeout (no 2 matching results within window): sends `CRASH_SUSPECT` to all RMs

### Sequencer
- Receives `REQUEST` from FE; atomically assigns monotonically increasing `seqNum`
- Stores every `EXECUTE` message in `historyBuffer` (enables replay for recovering replicas)
- Reliably multicasts `EXECUTE:seqNum:reqID:feHost:fePort:operation` to all 4 replicas **in parallel** using `ReliableUDPSender`
- On `NACK` from a replica (gap detected): replays missing messages from `historyBuffer`
- On `REPLICA_READY` from RM: updates its multicast target list and replays from last known seq so the new replica catches up

### Replica (4 instances)
- Each replica runs **3 office instances** (MTL, WPG, BNF) with full A3 business logic unchanged
- Uses `ExecutionGate` to enforce **total-order execution**: only processes seq N when all prior seqs are done
- Out-of-order `EXECUTE` messages buffered in `holdbackQueue` (TreeMap keyed by seqNum); sends `NACK` back to Sequencer requesting replay
- Sends `RESULT:seqNum:reqID:replicaID:payload` to FE after execution
- Supports `SET_BYZANTINE` toggle: Byzantine mode returns random/wrong results (for fault injection)
- Provides full state snapshot via `STATE_TRANSFER` (Base64-encoded Java serialization) on RM request

### Replica Manager (4 instances, one per replica)
- Launches co-located replica subprocess; monitors via **periodic heartbeats** (no heartbeat = crash)
- Receives `INCORRECT_RESULT` / `CRASH_SUSPECT` / `REPLACE_REQUEST` from FE
- **Consensus voting:** broadcasts `VOTE_BYZANTINE` or `VOTE_CRASH` to all peer RMs; acts on majority
- Replacement workflow (on majority vote):
  1. Sends `STATE_REQUEST` to a healthy replica
  2. Receives `STATE_TRANSFER` (Base64 snapshot of all vehicles, reservations, waitlists)
  3. Kills old replica subprocess; launches fresh one, sends `INIT_STATE` to load snapshot
  4. Broadcasts `REPLICA_READY` → Sequencer replays missed EXECUTEs to bring new replica current

---

## 4. Normal Request Flow

```
1. Client calls SOAP method (e.g., addVehicle)
2. FE generates REQ-5, sends REQUEST:REQ-5:localhost:9000:ADDVEHICLE:... to Sequencer
3. Sequencer assigns seqNum=5, stores in historyBuffer
   → multicasts EXECUTE:5:REQ-5:localhost:9000:ADDVEHICLE:... to R1, R2, R3, R4
4. Each replica's ExecutionGate: if seqNum == nextExpected → execute immediately
   else buffer in holdbackQueue, send NACK
5. Each replica executes addVehicle on MTL office, sends RESULT:5:REQ-5:R1:SUCCESS back to FE
6. FE collects: R1=SUCCESS, R2=SUCCESS → 2 match → returns SOAP response to client immediately
   (R3, R4 results arrive later and are discarded for this request)
```

---

## 5. Demo Scenarios

### Pre-Demo Setup

```bash
mvn clean compile
./demo-start.sh          # starts Sequencer, 4 RMs, FE in background (logs/ dir)
```

Verify system healthy: run `ManagerClient` option **3** (List Available Vehicles) — should succeed.

---

### Scenario A — Normal Operation (~3 min)

**Steps:**
1. Run option **1** (Add Vehicle): `MTLM1000` adds `MTLV0001 Sedan $100`
2. Run option **3** (List Vehicles): confirm vehicle appears
3. Run option **5** (Reserve): `MTLU1000` reserves `MTLV0001`
4. Run option **6** (Cancel): cancel the reservation

**What happens at each component:**
1. Client calls SOAP → FE receives at `:8080`, generates `REQ-N`
2. FE sends `REQUEST` to Sequencer via Reliable UDP
3. Sequencer assigns `seqNum`, multicasts `EXECUTE` to all 4 replicas in parallel
4. Each replica's `ExecutionGate` receives EXECUTE in order → executes business logic on MTL vehicleDB
5. Each replica sends `RESULT:seqNum:reqID:replicaID:payload` to FE `:9000`
6. FE: first 2 matching results → returns SOAP response to client

**Expected:** Client sees correct result. All 4 replicas remain in sync.

---

### Scenario B — Byzantine Fault (~5 min)

**Inject fault:**
```bash
echo -n "SET_BYZANTINE:true" | nc -u -w1 localhost 6003
```

**Steps:** Run ManagerClient option **3** three times, then wait 8–10 seconds, then run option **3** once more.

**What happens at each component (per request):**
1. Client → FE → Sequencer → `EXECUTE` to all 4 replicas (same as normal)
2. Replica 3 (Byzantine) returns wrong/random `RESULT`; Replicas 1, 2, 4 return correct `RESULT`
3. FE voting: R1=correct, R2=correct → **2 match → immediate success**; client gets correct response
4. FE detects R3 mismatch → increments `byzantineCount[3]`
5. After **3rd mismatch**: FE sends `REPLACE_REQUEST:3:BYZANTINE_THRESHOLD` to all 4 RMs
6. RM3 broadcasts `VOTE_BYZANTINE:3:RM3` to peers → majority vote reached → replacement starts
7. RM3 sends `STATE_REQUEST` to Replica 1 → receives Base64 state snapshot
8. RM3 kills Replica 3, launches fresh subprocess, sends `INIT_STATE` to load snapshot
9. RM3 sends `REPLICA_READY` to Sequencer + FE + peers
10. Sequencer replays buffered EXECUTEs from `lastSeq` → new Replica 3 catches up

**Expected log evidence:**
```
RM3: Byzantine replace requested for 3
RM3: Starting replica replacement
RM3: State transfer complete, lastSeq=N
Sequencer: 3 ready, replaying from seq N
```
**Client never sees FAIL throughout.**

---

### Scenario C — Crash Fault (~4 min)

**Inject fault:**
```bash
kill $(lsof -ti udp:6002 | head -n1)
```

**Steps:** Run ManagerClient option **3** once, wait 8–10 seconds, run option **3** again.

**What happens at each component:**
1. Client → FE → Sequencer → `EXECUTE` multicast to all 4 replicas
2. Replica 2 is dead — no response; Replicas 1, 3, 4 execute and return `RESULT`
3. FE collects: R1=correct, R3=correct → **2 match → returns correct response immediately**
4. FE timeout handler: Replica 2 never responded → sends `CRASH_SUSPECT:REQ-N:seqNum:2` to all RMs
5. RM2 independently detects missing heartbeat → broadcasts `VOTE_CRASH:2:RM2` to peers
6. Majority crash vote reached → RM2 replacement workflow:
   - `STATE_REQUEST` to Replica 1 → `STATE_TRANSFER` received
   - Fresh replica subprocess launched → `INIT_STATE` → new Replica 2 running
   - `REPLICA_READY` → Sequencer replays missed EXECUTEs

**Expected log evidence:**
```
RM2: Starting replica replacement
RM2: State transfer complete, lastSeq=N
Sequencer: 2 ready, replaying from seq N
```
**First request succeeds immediately; second request (post-recovery) shows all 4 replicas responding.**

---

### Scenario D — Simultaneous Crash + Byzantine (~3 min, if time allows)

**Inject both faults:**
```bash
kill $(lsof -ti udp:6002 | head -n1)
echo -n "SET_BYZANTINE:true" | nc -u -w1 localhost 6003
```

**Steps:** Run option **3** three times, wait 8–10 seconds, run option **3** once more.

**What happens at each component:**
1. Client → FE → Sequencer → `EXECUTE` to all 4 replicas
2. Replica 2 dead (no response); Replica 3 Byzantine (wrong result)
3. FE receives: R1=correct, R3=wrong, R4=correct → **R1 and R4 match → immediate success** (`f+1=2`)
4. FE triggers: `CRASH_SUSPECT` for Replica 2, `INCORRECT_RESULT` for Replica 3
5. After 3 Byzantine strikes on Replica 3: `REPLACE_REQUEST` → RM3 replacement starts
6. RM2 detects crash via heartbeat → `VOTE_CRASH` → replacement starts
7. **Both RM2 and RM3 run independent recovery workflows in parallel**
8. Both send `REPLICA_READY` → Sequencer replays for both new replicas

**Expected:** Client always gets correct result; both replacements complete; full 4-replica operation restored.

**Reset after scenarios:**
```bash
echo -n "SET_BYZANTINE:false" | nc -u -w1 localhost 6003  # reset Byzantine
./demo-stop.sh                                              # full stop
```

---

## 6. Anticipated TA Questions

### Architecture & Design

**Q: Why 4 replicas? Why not 3?**
> With 3 replicas tolerating 1 Byzantine fault, the voting threshold is `f+1 = 2`. But if 1 crashes simultaneously, only 2 respond — 1 Byzantine + 1 correct — you cannot distinguish the correct answer. 4 replicas ensures that even with 1 crash + 1 Byzantine, **2 correct replicas always respond and agree**, satisfying `f+1 = 2`.

**Q: How does voting work? What if results differ?**
> FE collects RESULT messages from all replicas. It compares them pairwise. As soon as **2 results are identical**, that is declared correct and returned to the client. A non-matching result increments that replica's `byzantineCount`. No result within timeout window triggers `CRASH_SUSPECT`.

**Q: Why use Kaashoek's Sequencer for total ordering instead of ISIS/PBFT?**
> The Sequencer approach is simpler to implement correctly and sufficient for our fault model (non-malicious Byzantine + crash). PBFT requires `3f+1 = 7` replicas for 2 faults. Our system only requires the Sequencer to be non-faulty, which is stated as an assumption. The Sequencer assigns a single monotonically increasing sequence number, ensuring all replicas execute in the same order.

**Q: What is the holdback queue? Why is it needed?**
> UDP is unreliable and unordered. A replica may receive `EXECUTE:seq=5` before `EXECUTE:seq=4` (e.g., due to network reordering). The `holdbackQueue` (a `TreeMap` keyed by seqNum) buffers out-of-order messages. When `seq=4` arrives, it processes `seq=4` then immediately drains the queue executing `seq=5`, `seq=6`, etc. A `NACK` is sent back to the Sequencer for any detected gap.

---

### Fault Handling

**Q: How does FE detect a Byzantine replica vs a correct one?**
> FE compares all received RESULT payloads. Any result that does not match the majority (the 2-matching answer) is flagged. The replica that sent the non-matching result has its `byzantineCount` incremented. Three consecutive mismatches triggers `REPLACE_REQUEST`. If the replica later sends correct results, the counter resets to 0.

**Q: How does crash detection work? What if a replica is just slow?**
> FE uses an adaptive timeout (`slowestResponseTime` tracking). If FE cannot collect 2 matching results within the timeout window, it sends `CRASH_SUSPECT`. RM also independently monitors heartbeats. Crash verdict requires **RM consensus voting** — if a majority of RMs agree the replica is crashed, replacement begins. This prevents false positives from transient slowness.

**Q: How does state transfer ensure consistency?**
> When a new replica is being initialized, the RM requests a state snapshot from a **healthy** replica via `STATE_REQUEST`. The healthy replica serializes all its data (vehicleDB, reservations, waitlists for all 3 offices) to Base64 via Java serialization and sends it as `STATE_TRANSFER`. The new replica loads this snapshot via `INIT_STATE`, then the Sequencer replays all `EXECUTE` messages from `lastSeq+1` onwards from its `historyBuffer` — bringing the new replica fully up-to-date.

**Q: What is the Sequencer's role during recovery?**
> On receiving `REPLICA_READY:replicaID:lastSeq` from an RM, the Sequencer: (1) updates its multicast target to include the new replica's address, and (2) replays all `EXECUTE` messages from `lastSeq+1` from its `historyBuffer` to the new replica. This ensures the new replica catches up with any operations that happened during the replacement window.

---

### Implementation Details

**Q: Why UDP instead of TCP for server-to-server communication?**
> UDP gives us full control over reliability semantics. We implemented `ReliableUDPSender` with ACK/NACK and exponential backoff on top of UDP. This lets us implement exactly-once delivery semantics and NACK-based replay — which TCP does not natively support and which is critical for the holdback queue + gap-detection design.

**Q: Is the Sequencer a single point of failure?**
> Yes — the Sequencer is assumed non-faulty per the assignment's stated fault model. The system is designed to tolerate **replica** failures, not Sequencer failure. In a production system, the Sequencer could be made fault-tolerant using a Paxos-elected leader or a replicated sequencer, but that is outside the scope of this assignment.

**Q: How do RMs achieve consensus? What prevents split-brain?**
> Each RM maintains a vote map. When a REPLACE event is triggered, each RM broadcasts its vote to all 4 RMs. Any RM that receives **a majority of votes (≥3 of 4)** for the same target proceeds with replacement. The `replacementInProgress` guard flag prevents concurrent replacements for the same replica. The first RM to reach majority quorum initiates the replacement workflow.

**Q: How does cross-office reservation work?**
> When a customer at MTL wants to reserve a vehicle from WPG: the MTL server instance within a replica sends a UDP request to the WPG server instance (inter-office UDP on ports 50xx). WPG processes it and replies. This inter-office communication happens **within each replica independently** — all 4 replicas perform the same cross-office operation in the same total order, so state remains consistent across replicas.

---

## 7. Team Roles

| Module | Owner | Student ID |
|--------|-------|-----------|
| Front End (FE) | Xueming Zhao | 40347760 |
| Replica Manager (RM) | Chen Qian | 27867808 |
| Sequencer | Anusha Gairola | 40347249 |
| Test Cases & Client | Yichen Huang | 40167688 |
