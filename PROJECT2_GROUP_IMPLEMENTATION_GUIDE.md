# PROJECT 2 GROUP IMPLEMENTATION GUIDE

## Purpose
This guide is the team checklist for building one shared Project 2 base version first.
After the group base is accepted, each member extends their own feature work.

## How To Use
1. Complete all Phase 1 items together (top to bottom — shared core first, then each role).
2. Confirm the Group Readiness Checklist is complete.
3. Move to Phase 2 for individual feature extension.

## Team Ownership
| Area | Owner |
|---|---|
| Front End (FE) | [Student 1] |
| Replica Manager (RM) | [Student 2] |
| Sequencer | [Student 3] |
| Test Cases and Client | [Student 4] |
| Shared Integration and Group Base | [group] |

---

## Project Architecture

Below is the full file tree. Items marked **[existing]** are from A3 and should not be deleted. Items marked **[NEW]** must be created for P2. Items marked **[MODIFY]** need changes described in the workflow blocks.

```
src/main/java/
├── model/                                ← [existing, unchanged]
│   ├── Vehicle.java                         vehicleID, vehicleType, licensePlate, reservationPrice
│   └── Reservation.java                     customerID, vehicleID, startDate, endDate, pricePaid
│
├── server/                               ← [existing + new files]
│   ├── VehicleReservationWS.java            [MODIFY] add holdback queue, snapshot, SET_BYZANTINE
│   ├── ServerPublisher.java                 [existing] keep as-is (used only for dev/test of A3 mode)
│   ├── UDPServer.java                       [MODIFY] add deliveredMsgId dedup + ACK reply
│   ├── ServerIdRules.java                   [existing] ID validation, extractOfficeID()
│   ├── DateRules.java                       [existing] date parsing, overlap
│   ├── ReservationQueries.java              [existing] reservation lookups
│   ├── WaitlistQueries.java                 [existing] waitlist lookups
│   ├── WaitlistEntry.java                   [existing] waitlist record
│   ├── RemoteResponseRules.java             [existing] response classification
│   ├── RemoteResponseType.java              [existing] enum
│   │
│   ├── PortConfig.java                      [NEW, group]    shared port constants
│   ├── UDPMessage.java                      [NEW, group]    message type enum + parse/serialize
│   ├── ReliableUDPSender.java               [NEW, group]    sender with ACK + retry + backoff
│   ├── ReplicaLauncher.java                 [NEW, group]    starts replica with UDP (no SOAP)
│   │
│   ├── FrontEnd.java                        [NEW, Student 1] SOAP endpoint + voting
│   ├── ReplicaManager.java                  [NEW, Student 2] heartbeat + failover + state transfer
│   └── Sequencer.java                       [NEW, Student 3] total-order multicast
│
├── client/                               ← [existing, unchanged]
│   ├── CustomerClient.java                  interactive SOAP client
│   ├── ManagerClient.java                   interactive SOAP client
│   ├── ClientRuntimeSupport.java            WSDL resolution, connection helpers
│   └── WsEndpoint.java                      WSDL URL holder
│
src/test/java/                            ← [Student 4 extends]
├── server/
│   ├── VehicleReservationWSTest.java        [existing] unit tests (dvrms.disable.udp=true)
│   └── ...                                  [existing] other test files
├── client/
│   └── ...                                  [existing] client tests
└── integration/                             [NEW, Student 4]
    └── ReplicationIntegrationTest.java      end-to-end P2 tests (T1–T21)
```

---

## Phase 1: Group Base Scope

### In Scope (Do Now)
- Build the shared project baseline from the design document (v3.2).
- Keep current Assignment 3 business rules unchanged.
- Add only the minimum shared structure needed for FE, RM, Sequencer, and replica collaboration.
- Keep code readable and consistent for everyone before personal extensions.

### Out of Scope (Do Later)
- Individual custom optimizations or personal refactors.
- Non-required framework/infrastructure additions (no Raft, Paxos, message brokers, etc.).
- Feature-specific extensions that are not needed for the group base milestone.

---

## Feature Workflow Blocks

### [group] Shared Core

- [ ] Owner: [group]
- [ ] Goal: build the common infrastructure that every component depends on
- [ ] Input/Dependency: current A3 codebase + design document v3.2

#### Step 1 — Create `PortConfig.java` (design doc §2.5)

Central place for all port numbers. Replaces the hardcoded `UDP_PORTS` map in `VehicleReservationWS.java:105-111` and the ports in `ServerPublisher.java:18-20`.

**Build steps:**
1. Create `src/main/java/server/PortConfig.java`.
2. Move all port constants here.
3. Update `VehicleReservationWS` and `ServerPublisher` to read from `PortConfig` instead of local maps.

```java
package server;

public final class PortConfig {
    private PortConfig() {}

    // Replica UDP ports (receive EXECUTE from Sequencer)
    public static final int REPLICA_1 = 6001;
    public static final int REPLICA_2 = 6002;
    public static final int REPLICA_3 = 6003;
    public static final int REPLICA_4 = 6004;
    public static final int[] ALL_REPLICAS = {REPLICA_1, REPLICA_2, REPLICA_3, REPLICA_4};

    // Replica Manager UDP ports
    public static final int RM_1 = 7001;
    public static final int RM_2 = 7002;
    public static final int RM_3 = 7003;
    public static final int RM_4 = 7004;
    public static final int[] ALL_RMS = {RM_1, RM_2, RM_3, RM_4};

    // Per-replica office ports (inter-office UDP, same as A3)
    // Replica 1: MTL=5001, WPG=5002, BNF=5003
    // Replica 2: MTL=5011, WPG=5012, BNF=5013
    // Replica 3: MTL=5021, WPG=5022, BNF=5023
    // Replica 4: MTL=5031, WPG=5032, BNF=5033
    public static int officePort(int replicaIndex, String office) {
        int base = 5001 + (replicaIndex - 1) * 10;
        switch (office) {
            case "MTL": return base;
            case "WPG": return base + 1;
            case "BNF": return base + 2;
            default: throw new IllegalArgumentException("Unknown office: " + office);
        }
    }

    // Front End
    public static final int FE_SOAP = 8080;
    public static final int FE_UDP  = 9000;

    // Sequencer
    public static final int SEQUENCER = 9100;
}
```

---

#### Step 2 — Create `UDPMessage.java` (design doc §4.4)

Shared message format. Follows the same colon-delimited pattern already used in `VehicleReservationWS.handleUDPRequest()` (line 975).

**Build steps:**
1. Create `src/main/java/server/UDPMessage.java`.
2. Define the message type enum.
3. Implement `parse(String raw)` — splits on `:` like `handleUDPRequest` already does.
4. Implement `serialize()` — joins fields with `:`.

```java
package server;

public class UDPMessage {

    public enum Type {
        // Core flow
        REQUEST,    // FE → Sequencer
        EXECUTE,    // Sequencer → Replicas
        RESULT,     // Replica → FE
        ACK,        // any → sender
        NACK,       // Replica → Sequencer

        // Failure handling
        INCORRECT_RESULT,  // FE → all RMs
        CRASH_SUSPECT,     // FE → all RMs
        REPLACE_REQUEST,   // FE → all RMs

        // RM coordination
        VOTE_BYZANTINE, VOTE_CRASH,
        SHUTDOWN, REPLICA_READY,

        // Heartbeat
        HEARTBEAT_CHECK, HEARTBEAT_ACK,

        // State transfer
        STATE_REQUEST, STATE_TRANSFER, INIT_STATE,

        // Testing
        SET_BYZANTINE
    }

    private final Type type;
    private final String[] fields;  // everything after the type token

    public UDPMessage(Type type, String... fields) {
        this.type = type;
        this.fields = fields;
    }

    /** Parse "TYPE:field1:field2:..." into a UDPMessage. */
    public static UDPMessage parse(String raw) {
        String[] parts = raw.split(":", -1);
        Type type = Type.valueOf(parts[0]);
        String[] fields = new String[parts.length - 1];
        System.arraycopy(parts, 1, fields, 0, fields.length);
        return new UDPMessage(type, fields);
    }

    /** Serialize back to "TYPE:field1:field2:..." */
    public String serialize() {
        StringBuilder sb = new StringBuilder(type.name());
        for (String f : fields) {
            sb.append(':').append(f);
        }
        return sb.toString();
    }

    public Type getType()          { return type; }
    public String getField(int i)  { return fields[i]; }
    public int fieldCount()        { return fields.length; }
}
```

**Message format reference (from design doc):**

| Message | Format | Direction |
|---|---|---|
| REQUEST | `REQUEST:reqID:feHost:fePort:op:params` | FE → Sequencer |
| EXECUTE | `EXECUTE:seqNum:reqID:feHost:fePort:op:params` | Sequencer → Replicas |
| RESULT | `RESULT:seqNum:reqID:replicaID:resultString` | Replica → FE |
| ACK | `ACK:msgId` | any → sender |
| NACK | `NACK:replicaID:seqStart:seqEnd` | Replica → Sequencer |

---

#### Step 3 — Create `ReliableUDPSender.java` (design doc §2.4)

Wraps the send-and-wait-for-ACK logic. The existing `UDPServer.java` already shows the socket pattern (buffer size 8192, `DatagramSocket`, `DatagramPacket`). The existing `sendUDPRequest()` in `VehicleReservationWS.java:1259-1287` shows the single-shot send pattern — this class adds retries on top.

**Build steps:**
1. Create `src/main/java/server/ReliableUDPSender.java`.
2. `send()` method: send packet, wait for `ACK:msgId` with timeout, retry with exponential backoff.
3. Reuse across FE, Sequencer, and RM — any component that needs reliable send.

```java
package server;

import java.net.*;
import java.nio.charset.StandardCharsets;

public class ReliableUDPSender {

    private static final int BUFFER_SIZE = 8192;
    private static final int INITIAL_TIMEOUT_MS = 500;
    private static final int MAX_RETRIES = 5;

    /**
     * Send a message and wait for ACK. Retries with exponential backoff.
     * @return true if ACK received, false if all retries exhausted.
     */
    public boolean send(String message, InetAddress address, int port, DatagramSocket socket) {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(data, data.length, address, port);

        int timeout = INITIAL_TIMEOUT_MS;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                socket.send(packet);
                socket.setSoTimeout(timeout);

                // Wait for ACK
                byte[] ackBuf = new byte[BUFFER_SIZE];
                DatagramPacket ackPacket = new DatagramPacket(ackBuf, ackBuf.length);
                socket.receive(ackPacket);

                String response = new String(ackPacket.getData(), 0, ackPacket.getLength(), StandardCharsets.UTF_8);
                if (response.startsWith("ACK:")) {
                    return true;
                }
            } catch (SocketTimeoutException e) {
                // Retry with doubled timeout: 500 → 1000 → 2000 → 4000 → 8000
                timeout *= 2;
            } catch (Exception e) {
                System.err.println("ReliableUDPSender error: " + e.getMessage());
                return false;
            }
        }
        return false; // all retries exhausted
    }
}
```

---

#### Step 4 — Modify `UDPServer.java` — add dedup + ACK (design doc §2.4)

The existing `UDPServer.java` already listens on a socket and dispatches to `handleUDPRequest()`. Add:
- A `deliveredMsgId` set to detect duplicates.
- Automatic ACK reply after processing.

**Build steps:**
1. Add `private final Set<String> deliveredMsgIds = ConcurrentHashMap.newKeySet();` field.
2. Before calling `servant.handleUDPRequest()`, extract `msgId` from the message.
3. If `msgId` is already in `deliveredMsgIds`, send ACK but skip execution.
4. Otherwise, execute, add `msgId` to set, send ACK + response.

```java
// In UDPServer.java — changes to the receive loop inside run()

// After: String request = new String(receivePacket.getData(), ...);
UDPMessage msg = UDPMessage.parse(request);

// ACK messages are handled silently (no re-dispatch)
if (msg.getType() == UDPMessage.Type.ACK) {
    continue;
}

// Extract msgId (first field for most messages)
String msgId = msg.getField(0);

// Dedup: if already delivered, ACK but don't re-execute
if (deliveredMsgIds.contains(msgId)) {
    String ack = "ACK:" + msgId;
    byte[] ackData = ack.getBytes(StandardCharsets.UTF_8);
    socket.send(new DatagramPacket(ackData, ackData.length,
        receivePacket.getAddress(), receivePacket.getPort()));
    continue;
}

// Execute and record
String response = servant.handleUDPRequest(request);
deliveredMsgIds.add(msgId);

// Send ACK back to sender
String ack = "ACK:" + msgId;
byte[] ackData = ack.getBytes(StandardCharsets.UTF_8);
socket.send(new DatagramPacket(ackData, ackData.length,
    receivePacket.getAddress(), receivePacket.getPort()));

// Send response (for EXECUTE messages, also send RESULT back to FE)
byte[] sendData = response.getBytes(StandardCharsets.UTF_8);
socket.send(new DatagramPacket(sendData, sendData.length,
    receivePacket.getAddress(), receivePacket.getPort()));
```

---

#### Step 5 — Modify `VehicleReservationWS.java` — holdback queue (design doc §3.5, §4.1)

The existing class has all business logic methods (`reserveVehicleLocal`, `cancelReservationLocal`, etc.) called from `handleUDPRequest()` at line 975. Add ordering enforcement before dispatch.

**Build steps:**
1. Add holdback queue fields.
2. Add a new method `handleExecute()` that checks sequence ordering before calling existing `handleUDPRequest()`.
3. Add `SET_BYZANTINE`, `HEARTBEAT_CHECK`, and `STATE_REQUEST` cases to the `handleUDPRequest()` switch.
4. Add `getStateSnapshot()` and `loadStateSnapshot()` methods.
5. Add `getNextExpectedSeq()` accessor (used by `ReplicaLauncher` for HEARTBEAT_ACK and INIT_STATE ACK).

```java
// Add these fields to VehicleReservationWS.java

// Holdback queue for total ordering (P2)
private int nextExpectedSeq = 0;
private final PriorityQueue<PendingExecute> holdbackQueue =
    new PriorityQueue<>(Comparator.comparingInt(p -> p.seqNum));

// Byzantine simulation flag (P2)
private volatile boolean byzantineMode = false;

// ---------- Inner class for holdback ----------
private static class PendingExecute {
    final int seqNum;
    final String reqID;
    final String feHost;
    final int fePort;
    final String operation;  // e.g., "RESERVE:cust:veh:start:end"

    PendingExecute(int seqNum, String reqID, String feHost, int fePort, String operation) {
        this.seqNum = seqNum;
        this.reqID = reqID;
        this.feHost = feHost;
        this.fePort = fePort;
        this.operation = operation;
    }
}
```

```java
// New method: handleExecute — called when replica receives EXECUTE from Sequencer

synchronized String handleExecute(int seqNum, String reqID,
                                   String feHost, int fePort, String operation) {
    if (seqNum < nextExpectedSeq) {
        // Duplicate — already executed, just ACK
        return "ACK:" + reqID;
    }
    if (seqNum > nextExpectedSeq) {
        // Out of order — buffer it
        holdbackQueue.add(new PendingExecute(seqNum, reqID, feHost, fePort, operation));
        // Send NACK for missing range
        return "NACK:" + serverID + ":" + nextExpectedSeq + ":" + (seqNum - 1);
    }
    // seqNum == nextExpectedSeq — execute in order
    String result = executeAndDeliver(seqNum, reqID, feHost, fePort, operation);

    // Drain any buffered messages that are now in order
    while (!holdbackQueue.isEmpty() && holdbackQueue.peek().seqNum == nextExpectedSeq) {
        PendingExecute next = holdbackQueue.poll();
        executeAndDeliver(next.seqNum, next.reqID, next.feHost, next.fePort, next.operation);
    }
    return result;
}

private String executeAndDeliver(int seqNum, String reqID,
                                  String feHost, int fePort, String operation) {
    // If Byzantine mode, return random garbage
    if (byzantineMode) {
        nextExpectedSeq++;
        return "RESULT:" + seqNum + ":" + serverID + ":BYZANTINE_RANDOM_" + System.nanoTime();
    }

    // Execute using existing handleUDPRequest (A3 business logic, unchanged)
    String result = handleUDPRequest(operation);
    nextExpectedSeq++;

    // Send RESULT back to FE (includes reqID for FE to match to pending request)
    String resultMsg = "RESULT:" + seqNum + ":" + reqID + ":" + serverID + ":" + result;
    sendResultToFE(feHost, fePort, resultMsg);

    return resultMsg;
}

private void sendResultToFE(String feHost, int fePort, String resultMsg) {
    try {
        DatagramSocket socket = new DatagramSocket();
        byte[] data = resultMsg.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(data, data.length,
            InetAddress.getByName(feHost), fePort));
        socket.close();
    } catch (Exception e) {
        System.err.println(serverID + ": Failed to send result to FE: " + e.getMessage());
    }
}
```

```java
// Add to handleUDPRequest() switch statement (around line 1010):

case "SET_BYZANTINE":
    byzantineMode = parts.length >= 2 && "true".equalsIgnoreCase(parts[1]);
    return "OK:BYZANTINE=" + byzantineMode;
case "HEARTBEAT_CHECK":
    return "HEARTBEAT_ACK:" + serverID + ":" + nextExpectedSeq;
case "STATE_REQUEST":
    return "STATE_TRANSFER:" + getStateSnapshot();
```

```java
// Add accessor for nextExpectedSeq (used by ReplicaLauncher for HEARTBEAT_ACK and INIT_STATE ACK):

int getNextExpectedSeq() {
    return nextExpectedSeq;
}
```

---

#### Step 6 — Add state snapshot methods to `VehicleReservationWS.java` (design doc §3.5 change #4)

Models (`Vehicle`, `Reservation`) already implement `Serializable`. WaitlistEntry needs `Serializable` added.

**Build steps:**
1. Add `Serializable` to `WaitlistEntry.java`.
2. Add `getStateSnapshot()` — serializes all state to a Base64 string.
3. Add `loadStateSnapshot()` — deserializes and replaces local state.

```java
// In VehicleReservationWS.java

/** Serialize full replica state for transfer. */
public String getStateSnapshot() {
    try {
        Map<String, Object> state = new HashMap<>();
        state.put("vehicleDB", new HashMap<>(vehicleDB));
        state.put("reservations", deepCopyReservations());
        state.put("waitList", deepCopyWaitList());
        state.put("customerBudget", new HashMap<>(customerBudget));
        state.put("crossOfficeCount", deepCopyCrossOffice());
        state.put("nextExpectedSeq", nextExpectedSeq);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos);
        oos.writeObject(state);
        oos.close();
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    } catch (IOException e) {
        throw new RuntimeException("Snapshot serialization failed", e);
    }
}

/** Load state from a snapshot string. */
@SuppressWarnings("unchecked")
public void loadStateSnapshot(String snapshot) {
    try {
        byte[] data = Base64.getDecoder().decode(snapshot);
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data));
        Map<String, Object> state = (Map<String, Object>) ois.readObject();
        ois.close();

        vehicleDB.clear();
        vehicleDB.putAll((Map<String, Vehicle>) state.get("vehicleDB"));
        reservations.clear();
        reservations.putAll((Map<String, List<Reservation>>) state.get("reservations"));
        waitList.clear();
        waitList.putAll((Map<String, List<WaitlistEntry>>) state.get("waitList"));
        customerBudget.clear();
        customerBudget.putAll((Map<String, Double>) state.get("customerBudget"));
        crossOfficeCount.clear();
        crossOfficeCount.putAll((Map<String, Set<String>>) state.get("crossOfficeCount"));
        nextExpectedSeq = (int) state.get("nextExpectedSeq");
    } catch (Exception e) {
        throw new RuntimeException("Snapshot loading failed", e);
    }
}
```

---

#### Step 7 — Create `ReplicaLauncher.java` (design doc §3.5 change #1)

Replaces `ServerPublisher.java` for P2 replicas. Each replica runs as its own process — no SOAP endpoint, only UDP.

**Build steps:**
1. Create `src/main/java/server/ReplicaLauncher.java`.
2. Accept `replicaId` (1–4) as argument.
3. Create a `VehicleReservationWS` instance for each office (MTL, WPG, BNF).
4. Start a UDP listener that receives EXECUTE messages and calls `handleExecute()`.

```java
package server;

import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * Starts one replica (3 offices: MTL, WPG, BNF) with UDP listener.
 * No SOAP endpoint — all requests come through the Sequencer.
 *
 * Usage: java server.ReplicaLauncher <replicaId>
 *        where replicaId = 1, 2, 3, or 4
 */
public class ReplicaLauncher {

    public static void main(String[] args) {
        int replicaId = Integer.parseInt(args[0]); // 1-4
        int replicaPort = PortConfig.ALL_REPLICAS[replicaId - 1];

        // Create the 3 office instances (same as A3, but no SOAP publish)
        VehicleReservationWS mtl = new VehicleReservationWS("MTL", PortConfig.officePort(replicaId, "MTL"));
        VehicleReservationWS wpg = new VehicleReservationWS("WPG", PortConfig.officePort(replicaId, "WPG"));
        VehicleReservationWS bnf = new VehicleReservationWS("BNF", PortConfig.officePort(replicaId, "BNF"));

        // Map office ID → server instance
        java.util.Map<String, VehicleReservationWS> offices = new java.util.HashMap<>();
        offices.put("MTL", mtl);
        offices.put("WPG", wpg);
        offices.put("BNF", bnf);

        System.out.println("Replica " + replicaId + " started on UDP port " + replicaPort);

        // Listen for EXECUTE messages from Sequencer
        try (DatagramSocket socket = new DatagramSocket(replicaPort)) {
            byte[] buf = new byte[8192];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String raw = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                // Parse: EXECUTE:seqNum:reqID:feHost:fePort:op:params
                // The "op:params" part is the A3 operation string (e.g., RESERVE:cust:veh:start:end)
                UDPMessage msg = UDPMessage.parse(raw);
                switch (msg.getType()) {
                    case EXECUTE: {
                        int seqNum   = Integer.parseInt(msg.getField(0));
                        String reqID = msg.getField(1);
                        String feHost = msg.getField(2);
                        int fePort   = Integer.parseInt(msg.getField(3));
                        // Remaining fields = "op:params" — reconstruct original operation string
                        StringBuilder op = new StringBuilder(msg.getField(4));
                        for (int i = 5; i < msg.fieldCount(); i++) {
                            op.append(':').append(msg.getField(i));
                        }

                        // Route to correct office based on operation target
                        String officeId = extractTargetOffice(op.toString());
                        VehicleReservationWS target = offices.getOrDefault(officeId, mtl);

                        target.handleExecute(seqNum, reqID, feHost, fePort, op.toString());

                        // Send ACK back to Sequencer
                        String ack = "ACK:" + seqNum;
                        byte[] ackData = ack.getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(ackData, ackData.length,
                            packet.getAddress(), packet.getPort()));
                        break;
                    }
                    case HEARTBEAT_CHECK: {
                        String reply = "HEARTBEAT_ACK:" + replicaId + ":" + mtl.getNextExpectedSeq();
                        byte[] replyData = reply.getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(replyData, replyData.length,
                            packet.getAddress(), packet.getPort()));
                        break;
                    }
                    case SET_BYZANTINE: {
                        boolean enable = "true".equalsIgnoreCase(msg.getField(0));
                        for (VehicleReservationWS office : offices.values()) {
                            office.handleUDPRequest("SET_BYZANTINE:" + enable);
                        }
                        String reply = "ACK:SET_BYZANTINE:" + enable;
                        byte[] replyData = reply.getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(replyData, replyData.length,
                            packet.getAddress(), packet.getPort()));
                        break;
                    }
                    case STATE_REQUEST: {
                        // Collect snapshot from all 3 offices
                        StringBuilder snapshot = new StringBuilder();
                        snapshot.append(mtl.getStateSnapshot()).append("|");
                        snapshot.append(wpg.getStateSnapshot()).append("|");
                        snapshot.append(bnf.getStateSnapshot());
                        String reply = "STATE_TRANSFER:" + replicaId + ":" + snapshot.toString();
                        byte[] replyData = reply.getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(replyData, replyData.length,
                            packet.getAddress(), packet.getPort()));
                        break;
                    }
                    case INIT_STATE: {
                        // Load snapshot into all 3 offices
                        // Format: INIT_STATE:mtlSnapshot|wpgSnapshot|bnfSnapshot
                        String[] snapshots = msg.getField(0).split("\\|");
                        mtl.loadStateSnapshot(snapshots[0]);
                        wpg.loadStateSnapshot(snapshots[1]);
                        bnf.loadStateSnapshot(snapshots[2]);
                        String reply = "ACK:INIT_STATE:" + replicaId + ":" + mtl.getNextExpectedSeq();
                        byte[] replyData = reply.getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(replyData, replyData.length,
                            packet.getAddress(), packet.getPort()));
                        break;
                    }
                    case ACK:
                        break; // silently ignore
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            System.err.println("Replica " + replicaId + " error: " + e.getMessage());
        }
    }

    /** Extract target office from operation string. Routes by operation type. */
    private static String extractTargetOffice(String operation) {
        String[] parts = operation.split(":", -1);
        String op = parts[0];
        switch (op) {
            case "FIND":
                return null; // broadcast — caller must query all 3 offices and merge
            case "LISTRES":
                return ServerIdRules.extractOfficeID(parts[1]); // customerID → office
            default:
                // RESERVE, CANCEL, WAITLIST, ATOMIC_UPDATE — vehicleID at parts[2]
                if (parts.length >= 3) {
                    return ServerIdRules.extractOfficeID(parts[2]);
                }
                return "MTL"; // fallback
        }
    }
}
```

#### Step 8 — Verify build stability
- Run `mvn -q test` — all existing A3 tests still pass.
- New files should compile without errors.

- [ ] Done Criteria: reliable UDP layer works, message format agreed, ports configured, holdback queue interface defined, replicas receive via UDP instead of SOAP, snapshot interface defined, build passes
- [ ] Notes: Keep A3 business logic completely unchanged. This step is only about adding the replication infrastructure around it.

---

### [Student 1] Front End (design doc §3.1)

- [ ] Owner: [Student 1]
- [ ] Input/Dependency: shared UDP layer + message format from Shared Core
- [ ] Goal: FE receives client SOAP requests, forwards to Sequencer via UDP, collects replica results, returns correct answer

#### Code: `FrontEnd.java`

**Build steps:**
1. Create `src/main/java/server/FrontEnd.java`.
2. Copy the `@WebService` annotation and all `@WebMethod` signatures from `VehicleReservationWS.java` (lines 255–925). The client-facing API is identical.
3. Each `@WebMethod` creates a REQUEST message, sends it to Sequencer, then blocks waiting for replica results.
4. Start a UDP listener thread on port 9000 that receives RESULT messages from replicas.
5. Implement the voting algorithm.
6. Add Byzantine/crash detection and reporting to RMs.

```java
package server;

import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@WebService(serviceName = "VehicleReservationService")
public class FrontEnd {

    // Pending request tracking: requestID → collected results + latch
    private final ConcurrentHashMap<String, RequestContext> pendingRequests = new ConcurrentHashMap<>();

    // Byzantine tracking per replica: replicaID → consecutive incorrect count
    private final ConcurrentHashMap<String, AtomicInteger> byzantineCount = new ConcurrentHashMap<>();

    // Adaptive timeout: track slowest response
    private final AtomicLong slowestResponseTime = new AtomicLong(2000); // initial 2s

    private final ReliableUDPSender sender = new ReliableUDPSender();
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    // ---------- Inner class ----------
    static class RequestContext {
        final String requestID;
        final long sentTime = System.currentTimeMillis();
        final ConcurrentHashMap<String, String> replicaResults = new ConcurrentHashMap<>(); // replicaID → result
        private final CompletableFuture<String> majorityFuture = new CompletableFuture<>();
        volatile int seqNum = -1; // set when first RESULT arrives (needed for §4.4 messages)

        RequestContext(String requestID) { this.requestID = requestID; }

        void addResult(String replicaID, String result, int seqNum) {
            if (this.seqNum < 0) this.seqNum = seqNum;
            replicaResults.put(replicaID, result);
            long matchCount = replicaResults.values().stream()
                .filter(v -> v.equals(result)).count();
            if (matchCount >= 2) {
                majorityFuture.complete(result); // release immediately on f+1 match (§3.1)
            }
        }

        String awaitMajority(long timeoutMs) {
            try {
                return majorityFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                return null; // timeout — caller votes on collected results
            } catch (Exception e) {
                return null;
            }
        }
    }

    public FrontEnd() {
        // Start UDP listener for RESULT messages from replicas
        new Thread(this::listenForResults, "FE-ResultListener").start();
    }

    // ===== @WebMethod — same signatures as VehicleReservationWS =====

    @WebMethod
    public String reserveVehicle(
            @WebParam(name = "customerID") String customerID,
            @WebParam(name = "vehicleID") String vehicleID,
            @WebParam(name = "startDate") String startDate,
            @WebParam(name = "endDate") String endDate) {
        String operation = "RESERVE:" + customerID + ":" + vehicleID + ":" + startDate + ":" + endDate;
        return forwardAndCollect(operation);
    }

    // ... same pattern for addVehicle, removeVehicle, listAvailableVehicle,
    //     updateReservation, cancelReservation, findVehicle,
    //     listCustomerReservations, addToWaitList

    // ===== Core: forward to Sequencer and collect results =====

    private String forwardAndCollect(String operation) {
        String reqID = "REQ-" + requestCounter.incrementAndGet();
        RequestContext ctx = new RequestContext(reqID);
        pendingRequests.put(reqID, ctx);

        // Step 1: Send REQUEST to Sequencer
        String requestMsg = "REQUEST:" + reqID + ":localhost:" + PortConfig.FE_UDP + ":" + operation;
        try {
            DatagramSocket socket = new DatagramSocket();
            sender.send(requestMsg, InetAddress.getByName("localhost"), PortConfig.SEQUENCER, socket);
            socket.close();
        } catch (Exception e) {
            return "FAIL: Could not reach Sequencer";
        }

        // Step 2: Wait for 2 matching results or timeout
        long timeout = 2 * slowestResponseTime.get();
        String majorityResult = ctx.awaitMajority(timeout);

        if (majorityResult != null) {
            // Fast path: 2 matching results arrived — return immediately (§3.1)
            // Update Byzantine counts and report dissenters
            for (var entry : ctx.replicaResults.entrySet()) {
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
            for (int port : PortConfig.ALL_REPLICAS) {
                String rid = "R" + port;
                if (!ctx.replicaResults.containsKey(rid)) {
                    sendToAllRMs("CRASH_SUSPECT:" + ctx.requestID + ":" + ctx.seqNum + ":" + rid);
                }
            }
            long elapsed = System.currentTimeMillis() - ctx.sentTime;
            slowestResponseTime.updateAndGet(prev -> Math.max(prev, elapsed));
            pendingRequests.remove(ctx.requestID);
            return majorityResult;
        }

        // Slow path: timeout — vote on whatever we collected
        return vote(ctx);
    }

    // ===== Voting algorithm (design doc §3.1 Phase 5) =====

    private String vote(RequestContext ctx) {
        // Count identical results
        ConcurrentHashMap<String, java.util.List<String>> resultToReplicas = new ConcurrentHashMap<>();
        for (var entry : ctx.replicaResults.entrySet()) {
            resultToReplicas.computeIfAbsent(entry.getValue(), k -> new CopyOnWriteArrayList<>())
                .add(entry.getKey());
        }

        // Find majority (f+1 = 2 matching)
        String majorityResult = null;
        java.util.List<String> majorityReplicas = null;
        for (var entry : resultToReplicas.entrySet()) {
            if (entry.getValue().size() >= 2) {
                majorityResult = entry.getKey();
                majorityReplicas = entry.getValue();
                break;
            }
        }

        if (majorityResult == null) {
            return "FAIL: No majority result";
        }

        // Update Byzantine counts
        for (var entry : ctx.replicaResults.entrySet()) {
            String replicaID = entry.getKey();
            if (entry.getValue().equals(majorityResult)) {
                // Correct — reset counter
                byzantineCount.computeIfAbsent(replicaID, k -> new AtomicInteger(0)).set(0);
            } else {
                // Incorrect — increment and report
                int count = byzantineCount.computeIfAbsent(replicaID, k -> new AtomicInteger(0))
                    .incrementAndGet();
                sendToAllRMs("INCORRECT_RESULT:" + ctx.requestID + ":" + ctx.seqNum + ":" + replicaID);
                if (count >= 3) {
                    sendToAllRMs("REPLACE_REQUEST:" + replicaID + ":BYZANTINE_THRESHOLD");
                }
            }
        }

        // Report crash for non-responding replicas
        // (replicas not in ctx.replicaResults after timeout)
        for (int port : PortConfig.ALL_REPLICAS) {
            String rid = "R" + port; // derive replicaID from port
            if (!ctx.replicaResults.containsKey(rid)) {
                sendToAllRMs("CRASH_SUSPECT:" + ctx.requestID + ":" + ctx.seqNum + ":" + rid);
            }
        }

        // Update slowest response time
        long elapsed = System.currentTimeMillis() - ctx.sentTime;
        slowestResponseTime.updateAndGet(prev -> Math.max(prev, elapsed));

        pendingRequests.remove(ctx.requestID);
        return majorityResult;
    }

    // ===== UDP listener for RESULT messages =====

    private void listenForResults() {
        try (DatagramSocket socket = new DatagramSocket(PortConfig.FE_UDP)) {
            byte[] buf = new byte[8192];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String raw = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                // Parse: RESULT:seqNum:reqID:replicaID:resultString
                UDPMessage msg = UDPMessage.parse(raw);
                if (msg.getType() == UDPMessage.Type.RESULT) {
                    int seqNum = Integer.parseInt(msg.getField(0));
                    String reqID = msg.getField(1);
                    String replicaID = msg.getField(2);
                    String result = msg.getField(3);

                    RequestContext ctx = pendingRequests.get(reqID);
                    if (ctx != null) {
                        ctx.addResult(replicaID, result, seqNum);
                    }
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
}
```

- [ ] Done Criteria: FE sends request to Sequencer, collects results from replicas, returns correct result via voting, reports Byzantine/crash to RMs
- [ ] Notes: FE does not send requests in parallel (one at a time per the lecture Phase 1 constraint). Publish SOAP at `http://localhost:8080/fe` using `Endpoint.publish()` in a new main method or in `FrontEnd` itself.

---

### [Student 2] Replica Manager (design doc §3.2)

- [ ] Owner: [Student 2]
- [ ] Input/Dependency: shared UDP layer + message format; FE sends INCORRECT_RESULT / CRASH_SUSPECT / REPLACE_REQUEST
- [ ] Goal: each RM monitors its co-located replica, participates in consensus, and handles replacement + state transfer

#### Code: `ReplicaManager.java`

**Build steps:**
1. Create `src/main/java/server/ReplicaManager.java`.
2. Constructor takes `replicaId` (1–4) and spawns the co-located replica process.
3. Start a heartbeat thread — periodically sends `HEARTBEAT_CHECK` to replica port.
4. Start a UDP listener on the RM port (7001–7004) for messages from FE and other RMs.
5. Implement Byzantine vote + crash vote handlers.
6. Implement state transfer: request snapshot from healthy replica, send to new replica.

```java
package server;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Replica Manager — one per replica.
 * Usage: java server.ReplicaManager <replicaId>
 */
public class ReplicaManager {

    private final int replicaId;          // 1-4
    private final int rmPort;             // 7001-7004
    private final int replicaPort;        // 6001-6004
    private Process replicaProcess;       // OS process for the replica
    private final ReliableUDPSender sender = new ReliableUDPSender();

    // Heartbeat config
    private static final int HEARTBEAT_INTERVAL_MS = 3000;
    private static final int HEARTBEAT_TIMEOUT_MS = 2000;

    public ReplicaManager(int replicaId) {
        this.replicaId = replicaId;
        this.rmPort = PortConfig.ALL_RMS[replicaId - 1];
        this.replicaPort = PortConfig.ALL_REPLICAS[replicaId - 1];
    }

    public void start() {
        // Step 1: Launch replica process
        launchReplica();

        // Step 2: Start heartbeat thread
        new Thread(this::heartbeatLoop, "RM" + replicaId + "-Heartbeat").start();

        // Step 3: Listen for messages from FE and other RMs
        listenForMessages();
    }

    // ===== Replica lifecycle =====

    private void launchReplica() {
        try {
            // Launch ReplicaLauncher as separate process
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

    // ===== Heartbeat =====

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

    // ===== Message listener =====

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

    // ===== Byzantine replacement (design doc §3.2) =====

    private void handleByzantineReplace(UDPMessage msg, DatagramSocket socket) {
        String faultyReplicaId = msg.getField(0);
        System.out.println("RM" + replicaId + ": Byzantine replace requested for " + faultyReplicaId);

        // Broadcast VOTE_BYZANTINE to all RMs (plain UDP — receivers don't ACK)
        String vote = "VOTE_BYZANTINE:" + faultyReplicaId + ":" + replicaId;
        byte[] data = vote.getBytes(StandardCharsets.UTF_8);
        for (int rmPort : PortConfig.ALL_RMS) {
            try {
                socket.send(new DatagramPacket(data, data.length,
                    InetAddress.getByName("localhost"), rmPort));
            } catch (Exception e) { /* log */ }
        }
    }

    private void handleCrashSuspect(UDPMessage msg, DatagramSocket socket) {
        // CRASH_SUSPECT:reqID:seqNum:replicaID (§4.4)
        String suspectedId = msg.getField(2);
        // Heartbeat the suspected replica's port, not our own
        int suspectedPort = PortConfig.ALL_REPLICAS[Integer.parseInt(suspectedId) - 1];
        boolean alive = sendHeartbeatTo(suspectedPort);
        // Broadcast vote to all RMs (plain UDP, includes voter ID)
        String vote = "VOTE_CRASH:" + suspectedId + ":"
            + (alive ? "ALIVE" : "CRASH_CONFIRMED") + ":" + replicaId;
        byte[] voteData = vote.getBytes(StandardCharsets.UTF_8);
        for (int rmPort : PortConfig.ALL_RMS) {
            try {
                socket.send(new DatagramPacket(voteData, voteData.length,
                    InetAddress.getByName("localhost"), rmPort));
            } catch (Exception e) { /* log */ }
        }
    }

    private void handleVote(UDPMessage msg, DatagramSocket socket) {
        // Collect and tally votes
        // If majority agrees: perform replacement + state transfer
    }

    // ===== State transfer (design doc §3.2) =====

    private void handleStateRequest(UDPMessage msg, DatagramSocket socket, DatagramPacket from) {
        // Request snapshot from co-located replica
        // Send STATE_TRANSFER response back to requesting RM
    }

    private void replaceReplica() {
        // Step 1: Kill faulty replica (Byzantine) or skip (crash — already dead)
        killReplica();

        // Step 2: Launch fresh replica
        launchReplica();

        // Step 3: Request state from lowest-ID healthy replica
        // Send STATE_REQUEST to healthy RM → get snapshot → send INIT_STATE to new replica

        // Step 4: Notify Sequencer + FE
        // Send REPLICA_READY to Sequencer and FE
    }

    // ===== Main =====

    public static void main(String[] args) {
        int id = Integer.parseInt(args[0]);
        new ReplicaManager(id).start();
    }
}
```

- [ ] Done Criteria: RM can start/stop its replica, heartbeat works, Byzantine and crash voting works, state transfer restores a replacement replica to correct state
- [ ] Notes: If a crash kills both replica and its co-located RM, only 3 RMs vote. The majority rule operates on reachable RMs — 2 out of 3 is sufficient.

---

### [Student 3] Sequencer (design doc §3.3, §4.1)

- [ ] Owner: [Student 3]
- [ ] Input/Dependency: shared UDP layer + message format; FE sends REQUEST messages
- [ ] Goal: assign monotonically increasing sequence numbers and reliably multicast to all replicas (Kaashoek's variant)

#### Code: `Sequencer.java`

**Build steps:**
1. Create `src/main/java/server/Sequencer.java`.
2. Listen on UDP port 9100 for REQUEST messages from FE.
3. On each REQUEST: assign `seqNum` from `sequenceCounter`, build EXECUTE message, multicast to all 4 replicas.
4. Track ACKs per message. Retransmit to non-ACKing replicas.
5. Store all EXECUTE messages in `historyBuffer` for NACK replay and recovery catch-up.
6. Handle NACK (resend range) and REPLICA_READY (replay from lastSeq+1).

```java
package server;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Sequencer — single instance, assumed failure-free.
 * Assigns total order to all requests and reliably multicasts to replicas.
 *
 * Usage: java server.Sequencer
 */
public class Sequencer {

    private final AtomicInteger sequenceCounter = new AtomicInteger(0);

    // History: seqNum → full EXECUTE message string (for NACK replay)
    private final ConcurrentHashMap<Integer, String> historyBuffer = new ConcurrentHashMap<>();

    // Track ACKs: seqNum → set of replica ports that have ACKed
    private final ConcurrentHashMap<Integer, ConcurrentHashMap.KeySetView<Integer, Boolean>> ackTracker =
        new ConcurrentHashMap<>();

    // Current replica addresses (updated on REPLICA_READY)
    private final CopyOnWriteArrayList<InetSocketAddress> replicaAddresses = new CopyOnWriteArrayList<>();

    private final ReliableUDPSender sender = new ReliableUDPSender();

    public Sequencer() {
        // Initialize default replica addresses
        for (int port : PortConfig.ALL_REPLICAS) {
            try {
                replicaAddresses.add(new InetSocketAddress(InetAddress.getByName("localhost"), port));
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    public void start() {
        System.out.println("Sequencer listening on port " + PortConfig.SEQUENCER);

        try (DatagramSocket socket = new DatagramSocket(PortConfig.SEQUENCER)) {
            byte[] buf = new byte[8192];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String raw = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                UDPMessage msg = UDPMessage.parse(raw);

                switch (msg.getType()) {
                    case REQUEST:
                        handleRequest(msg, socket);
                        break;
                    case ACK:
                        handleAck(msg, packet);
                        break;
                    case NACK:
                        handleNack(msg, socket, packet);
                        break;
                    case REPLICA_READY:
                        handleReplicaReady(msg, socket);
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            System.err.println("Sequencer error: " + e.getMessage());
        }
    }

    // ===== Handle REQUEST from FE =====

    private void handleRequest(UDPMessage msg, DatagramSocket socket) {
        // REQUEST fields: reqID, feHost, fePort, op, params...
        int seqNum = sequenceCounter.getAndIncrement();

        // Build EXECUTE message: EXECUTE:seqNum:reqID:feHost:fePort:op:params
        StringBuilder execute = new StringBuilder("EXECUTE:" + seqNum);
        for (int i = 0; i < msg.fieldCount(); i++) {
            execute.append(':').append(msg.getField(i));
        }
        String executeMsg = execute.toString();

        // Store in history
        historyBuffer.put(seqNum, executeMsg);

        // Initialize ACK tracker
        ackTracker.put(seqNum, ConcurrentHashMap.newKeySet());

        // Multicast to all replicas
        multicast(executeMsg, socket);

        System.out.println("Sequencer: assigned seq#" + seqNum + " to " + msg.getField(0));
    }

    // ===== Reliable multicast to all replicas =====

    private void multicast(String message, DatagramSocket ignored) {
        for (InetSocketAddress addr : replicaAddresses) {
            // Each thread creates its own socket — sharing is not thread-safe
            new Thread(() -> {
                try (DatagramSocket sendSocket = new DatagramSocket()) {
                    boolean acked = sender.send(message, addr.getAddress(), addr.getPort(), sendSocket);
                    if (!acked) {
                        System.err.println("Sequencer: replica at " + addr.getPort() + " unresponsive");
                    }
                } catch (Exception e) {
                    System.err.println("Sequencer: multicast send error: " + e.getMessage());
                }
            }).start();
        }
    }

    // ===== Handle ACK from replica =====

    private void handleAck(UDPMessage msg, DatagramPacket from) {
        int seqNum = Integer.parseInt(msg.getField(0));
        var acks = ackTracker.get(seqNum);
        if (acks != null) {
            // Identify replica by source port
            acks.add(from.getPort());
            if (acks.size() >= PortConfig.ALL_REPLICAS.length) {
                ackTracker.remove(seqNum);
            }
        }
    }

    // ===== Handle NACK — replay missing messages =====

    private void handleNack(UDPMessage msg, DatagramSocket socket, DatagramPacket from) {
        // NACK fields: replicaID, seqStart, seqEnd
        String replicaID = msg.getField(0);
        int seqStart = Integer.parseInt(msg.getField(1));
        int seqEnd = Integer.parseInt(msg.getField(2));

        System.out.println("Sequencer: NACK from " + replicaID + " for seq " + seqStart + "-" + seqEnd);

        // Replay to requesting replica ONLY (not all replicas)
        InetAddress requesterAddr = from.getAddress();
        int requesterPort = from.getPort();
        for (int seq = seqStart; seq <= seqEnd; seq++) {
            String historicMsg = historyBuffer.get(seq);
            if (historicMsg != null) {
                final String msgToSend = historicMsg;
                new Thread(() -> {
                    try (DatagramSocket sendSocket = new DatagramSocket()) {
                        sender.send(msgToSend, requesterAddr, requesterPort, sendSocket);
                    } catch (Exception e) {
                        System.err.println("Sequencer: replay send failed: " + e.getMessage());
                    }
                }).start();
            }
        }
    }

    // ===== Handle REPLICA_READY — catch-up after recovery =====

    private void handleReplicaReady(UDPMessage msg, DatagramSocket socket) {
        // REPLICA_READY fields: replicaID, host, port, lastSeqNum
        String replicaID = msg.getField(0);
        String host = msg.getField(1);
        int replicaPort = Integer.parseInt(msg.getField(2));
        int lastSeq = Integer.parseInt(msg.getField(3));

        System.out.println("Sequencer: " + replicaID + " ready, replaying from seq " + (lastSeq + 1));

        // Replay all messages after lastSeq to recovered replica only
        int current = sequenceCounter.get();
        try {
            InetAddress addr = InetAddress.getByName(host);
            for (int seq = lastSeq + 1; seq < current; seq++) {
                String historicMsg = historyBuffer.get(seq);
                if (historicMsg != null) {
                    final String msgToSend = historicMsg;
                    new Thread(() -> {
                        try (DatagramSocket sendSocket = new DatagramSocket()) {
                            sender.send(msgToSend, addr, replicaPort, sendSocket);
                        } catch (Exception e) {
                            System.err.println("Sequencer: replay send failed: " + e.getMessage());
                        }
                    }).start();
                }
            }
        } catch (Exception e) {
            System.err.println("Sequencer: replay to " + replicaID + " failed: " + e.getMessage());
        }

        // Update replica address list
        try {
            InetSocketAddress newAddr = new InetSocketAddress(
                InetAddress.getByName(host), replicaPort);
            replicaAddresses.removeIf(a -> a.getPort() == replicaPort);
            replicaAddresses.add(newAddr);
        } catch (Exception e) { /* log */ }
    }

    // ===== Main =====

    public static void main(String[] args) {
        new Sequencer().start();
    }
}
```

- [ ] Done Criteria: Sequencer receives FE requests, assigns sequence numbers, reliably delivers to all replicas, handles NACKs and recovery replay
- [ ] Notes: Sequencer is assumed failure-free (per project spec). It is not replicated.

---

### [Student 4] Test Cases and Client (design doc §3.4, §5.1–5.5)

- [ ] Owner: [Student 4]
- [ ] Input/Dependency: all components running (FE, Sequencer, 4 replicas, 4 RMs)
- [ ] Goal: validate the group base works for normal operations and all failure scenarios

#### Code: `ReplicationIntegrationTest.java`

**Build steps:**
1. Create `src/test/java/integration/ReplicationIntegrationTest.java`.
2. `@BeforeAll`: start FE, Sequencer, 4 ReplicaLaunchers, 4 ReplicaManagers as separate processes (or in-process threads for speed).
3. `@AfterAll`: shut down all processes.
4. Each test sends SOAP requests through the FE (reuse existing `ClientRuntimeSupport.connectToOffice()` pattern).
5. Add helper methods for Byzantine simulation and crash simulation.

```java
package integration;

import client.ClientRuntimeSupport;
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

    // SOAP client to FE (port 8080)
    private static generated.client.VehicleReservationService feService;

    @BeforeAll
    static void startSystem() throws Exception {
        // Start Sequencer
        new Thread(() -> new Sequencer().start()).start();

        // Start 4 Replicas
        for (int i = 1; i <= 4; i++) {
            final int id = i;
            new Thread(() -> ReplicaLauncher.main(new String[]{String.valueOf(id)})).start();
        }

        // Start 4 RMs
        for (int i = 1; i <= 4; i++) {
            final int id = i;
            new Thread(() -> new ReplicaManager(id).start()).start();
        }

        // Start FE (SOAP endpoint)
        FrontEnd fe = new FrontEnd();
        javax.xml.ws.Endpoint.publish("http://localhost:" + PortConfig.FE_SOAP + "/fe", fe);

        Thread.sleep(3000); // wait for startup

        // Connect SOAP client
        // (similar to ClientRuntimeSupport.connectToOffice pattern)
    }

    @AfterAll
    static void stopSystem() {
        // Shutdown all components
    }

    // ===== T1–T5: Normal operation =====

    @Test @Order(1)
    void t1_vehicleCrud() {
        // Add, list, remove vehicle through FE
        // Verify result is consistent
    }

    @Test @Order(2)
    void t2_localReservation() {
        // Reserve a vehicle; budget deducted
        // Verify via listCustomerReservations
    }

    @Test @Order(3)
    void t3_crossOfficeReservation() {
        // MTLU1111 reserves WPG1001
    }

    @Test @Order(4)
    void t4_concurrentRequests() {
        // Two threads reserve same vehicle simultaneously
        // One succeeds, one fails — deterministic across replicas
    }

    @Test @Order(5)
    void t5_waitlist() {
        // Second customer tries same vehicle → waitlisted
    }

    // ===== T6–T10: Byzantine failure =====

    @Test @Order(6)
    void t6_byzantineFirstStrike() {
        enableByzantine(3, true);  // R3 goes Byzantine
        // Send request → FE returns correct from R1/R2/R4
    }

    @Test @Order(7)
    void t7_byzantineSecondStrike() {
        // R3 still Byzantine → byzantineCount = 2
    }

    @Test @Order(8)
    void t8_byzantineThirdStrikeReplace() {
        // byzantineCount = 3 → triggers replacement
    }

    @Test @Order(9)
    void t9_afterReplacement() {
        // New R3 is correct
    }

    @Test @Order(10)
    void t10_byzantineCounterReset() {
        enableByzantine(3, false);
        // Correct response resets counter
    }

    // ===== T11–T14: Crash failure =====

    @Test @Order(11)
    void t11_crashDetection() {
        // Kill R2 process → FE returns from 3 matching replicas
    }

    @Test @Order(12)
    void t12_crashRecovery() {
        // RMs detect + replace R2
    }

    @Test @Order(13)
    void t13_stateTransfer() {
        // New R2 gets state → subsequent requests match all 4
    }

    @Test @Order(14)
    void t14_operationDuringRecovery() {
        // System works with 3 replicas while R2 recovers
    }

    // ===== T15–T17: Simultaneous failure =====

    @Test @Order(15)
    void t15_crashPlusByzantine() {
        // Kill R2 + Byzantine R3 → R1/R4 match (f+1 = 2)
    }

    @Test @Order(16)
    void t16_dualRecovery() {
        // Both R2 and R3 replaced
    }

    @Test @Order(17)
    void t17_stateConsistencyAfterRecovery() {
        // All 4 replicas have identical state
    }

    // ===== T18–T21: Edge cases =====

    @Test @Order(18)
    void t18_packetLossRetransmit() {
        // Simulate packet loss → Sequencer retransmits → R4 ACKs
    }

    @Test @Order(19)
    void t19_outOfOrderDelivery() {
        // seq#5 before seq#4 → holdback queue buffers → correct order
    }

    @Test @Order(20)
    void t20_threeSimultaneousClients() {
        // 3 threads send requests → unique seq# each → same total order
    }

    @Test @Order(21)
    void t21_fullCrossOfficeFlow() {
        // reserve → update → cancel across offices
    }

    // ===== Helpers =====

    /** Send SET_BYZANTINE to a replica. */
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
```

**Failure simulation methods:**
- **Byzantine:** send `SET_BYZANTINE:true` via UDP to replica port → `handleUDPRequest()` returns random results. `SET_BYZANTINE:false` restores normal.
- **Crash:** kill the replica process directly (via `Process.destroyForcibly()` or system kill). RM detects via heartbeat failure.

- [ ] Done Criteria: all 21 test cases have expected results defined; group base passes at least T1–T5 (normal operation) end-to-end
- [ ] Notes: T6–T21 may only fully pass after individual role implementations are complete. For group base, focus on T1–T5 and verify the infrastructure supports running the failure tests.

---

## Group Readiness Checklist (Before Phase 2)

### Build
- [ ] `mvn -q test` passes — baseline: `Tests run: 73, Failures: 0, Errors: 0, Skipped: 3`

### Integration
- [ ] FE sends a REQUEST to Sequencer via UDP and receives ACK back
- [ ] Sequencer multicasts EXECUTE to all 4 replicas and collects ACKs
- [ ] Replicas send RESULT back to FE via UDP
- [ ] At least one normal operation (T1 or T2) passes end-to-end through the full FE → Sequencer → Replica → FE path
- [ ] RM heartbeat check works for at least one replica

### Sign-Off
- [ ] Sign-off by [group]

## Guide Self-Check
- [ ] Only these placeholders are used: [Student 1], [Student 2], [Student 3], [Student 4], [group]
- [ ] All 5 workflow blocks exist and follow the same checklist format
- [ ] Phase 1 and Phase 2 are clearly separated
- [ ] Baseline validation step is present with current status

## Phase 2 Placeholder
After group base approval, each student extends own feature branch.
