# FT-DVRMS: Fault-Tolerant Distributed Vehicle Reservation Management System

COMP 6231 — Distributed System Design, Winter 2026, Concordia University

Project 2: Software Failure Tolerant and Highly Available DVRMS using Active Replication with 4 Replicas.

## Overview

FT-DVRMS extends the Assignment 3 JAX-WS vehicle reservation system into a fault-tolerant, highly available distributed system. The system tolerates **simultaneous 1 non-malicious Byzantine fault + 1 crash fault** using active replication (state machine replication) with 4 replicas.

Three rental offices — Montreal (MTL), Winnipeg (WPG), and Banff (BNF) — serve customers and managers through a single Front End that provides replication transparency. All A3 business logic is preserved unchanged.

## Architecture

**Active Replication:** All 4 replicas start from the same initial state and execute the same operations in the same total order (state machine replication). With 1 Byzantine + 1 crash, 3 replicas respond, at most 1 is faulty, so 2 correct replicas always match (f+1 = 2).

**Kaashoek's Sequencer-Based Total Order:**

1. FE sends each request **only to the Sequencer**
2. Sequencer assigns a monotonically increasing sequence number and reliably multicasts request + seq# to all 4 replicas
3. Replicas execute in total order using a holdback queue keyed by sequence number

**Communication:** Client-to-FE uses SOAP/HTTP (JAX-WS). All server-side communication uses UDP with a reliability layer (ACK/NACK, exponential backoff).

## Components

| Component | Role |
|---|---|
| **Front End** | SOAP endpoint, forwards to Sequencer, voting (2 identical = correct), Byzantine/crash detection |
| **Sequencer** | Assigns seq#, reliably multicasts to replicas, replays from history buffer on NACK |
| **Replica Manager** | One per replica; heartbeat monitoring, consensus voting, replacement + state transfer |
| **Replica** | State machine with holdback queue, state snapshot/restore, Byzantine mode toggle |

## Documentation Archive

Historical implementation/workflow markdown documents were archived under `archived/`.
The active closeout audit remains `PROJECT2_GROUP_BASE_AUDIT.md` in the project root.

## Project Structure

```
src/main/java/
  model/
    Vehicle.java, Reservation.java          [A3] data models
  server/
    VehicleReservationWS.java               [A3+P2] business logic + holdback queue + snapshot
    UDPServer.java                          [A3+P2] inter-office UDP + dedup/ACK
    PortConfig.java                         [P2] shared port constants
    UDPMessage.java                         [P2] message type enum + parse/serialize
    ReliableUDPSender.java                  [P2] ACK + retry + exponential backoff
    ReplicaLauncher.java                    [P2] starts replica with UDP listener
    FrontEnd.java                           [P2] SOAP endpoint + voting
    Sequencer.java                          [P2] total-order multicast
    ReplicaManager.java                     [P2] heartbeat + failover + state transfer
    ServerPublisher.java                    [A3] standalone SOAP publisher (dev/debug)
    ServerIdRules.java, DateRules.java, ... [A3] helpers (unchanged)
  client/
    CustomerClient.java, ManagerClient.java [A3] interactive SOAP clients
src/test/java/
  integration/
    ReplicationIntegrationTest.java         [P2] end-to-end test stubs (T1-T21)
```

## Test Scenarios (Design Doc §5)

| ID | Category | Scenario |
|---|---|---|
| T1-T5 | Normal | Vehicle CRUD, reservations, cross-office, concurrent requests, waitlist |
| T6-T10 | Byzantine | 1/2/3 consecutive faults, replacement+recovery, counter reset on correct |
| T11-T14 | Crash | Timeout detection, RM consensus, state transfer, requests during recovery |
| T15-T17 | Simultaneous | Byzantine+crash together, dual recovery, state consistency verification |
| T18-T21 | Edge cases | Retransmission, holdback ordering, concurrent clients, full cross-office flow |

## Quick Start

See [SETUP.md](SETUP.md) for full instructions.

```bash
mvn clean compile

# In separate terminals:
java -cp target/classes server.Sequencer
java -cp target/classes server.ReplicaLauncher 1   # repeat for 2, 3, 4
java -cp target/classes server.ReplicaManager 1    # repeat for 2, 3, 4
java -cp target/classes server.FrontEnd

# Build and run clients:
./build-client.sh --wsdl http://localhost:8080/fe?wsdl
java -cp bin client.CustomerClient
```

## Team

| Role | Owner        |id |
|---|--------------|-------|
| Front End (FE) | Xueming Zhao |40347760
| Replica Manager (RM) | Chen Qian    |27867808
| Sequencer | Anusha Gairola |40347249
| Test Cases & Client | Yichen Huang |40167688
