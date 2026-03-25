# PROJECT 2 SHARED CONFIG IMPLEMENTATION

## Purpose
This is the shared baseline guide for the whole team.
All student implementation work builds on this file first.

## Source of Truth and Alignment
Use this authority order for every decision:
1. `Project.6231w26.txt` (project requirements)
2. `FT-DVRMS_Project2_Design_v3.3.docx` (approved design)
3. `FT-DVRMS_Project2_Design_v3.3_ALIGNMENT_ADDENDUM.md` (wording/contract alignment)

Related role docs:
- `PROJECT2_STUDENT1_IMPLEMENTATION.md`
- `PROJECT2_STUDENT2_IMPLEMENTATION.md`
- `PROJECT2_STUDENT3_IMPLEMENTATION.md`
- `PROJECT2_STUDENT4_IMPLEMENTATION.md`

## Global Rules (Mandatory)
### A3-First Rule
- Assignment 3 business logic is the default contract.
- Do not change A3 business behavior unless strictly necessary for Project 2 replication/recovery behavior.

### Necessary-Change Note (Required for Any A3 Deviation)
When a behavior differs from A3, document:
- Source clause: requirement/design/addendum reference
- Why required: replication/recovery reason
- Impact: exact affected operation(s)
- Risk check: why this does not break other A3 rules

Template:

| Field | Entry |
|---|---|
| Source clause | |
| Change summary | |
| Affected operations/files | |
| Why unavoidable | |
| Validation evidence | |

### Phase Gate
- Student-specific implementation starts only after this shared baseline checklist passes.

## Shared Baseline Scope (Group-Owned)
Only group-owned common items belong here:
- Shared ports/config and canonical constants
- Shared UDP message contract and parsing rules
- Shared reliable UDP send contract
- Shared replica launch topology rules
- Shared cross-cutting run/startup constraints

## Phase 1 Shared Baseline Checklist

| Item | Expected result | Primary file(s) | Traceability |
|---|---|---|---|
| Port constants centralized | No hardcoded replication ports drift from common config | `src/main/java/server/PortConfig.java` | Requirement: UDP-based replica/RM topology; Design shared architecture |
| Message contract centralized | Canonical message family used consistently | `src/main/java/server/UDPMessage.java` | Requirement: FE->Sequencer->Replicas flow; Design protocol section |
| Reliable sender available | ACK + bounded retry/backoff shared utility | `src/main/java/server/ReliableUDPSender.java` | Requirement: reliable communication over UDP |
| Launch strategy consistent | RM-owned replica startup in integration path (no duplicate launch in same run) | `src/test/java/integration/ReplicationIntegrationTest.java`, RM startup path | Design recovery/startup behavior |
| A3 guardrail preserved | No unnecessary business logic rewrite | `src/main/java/server/VehicleReservationWS.java` and cross-cutting callers | Design 1.3 preserved business logic |

## Shared Runtime Gate (Must Pass Before Role Work)
1. `mvn -q -DskipTests compile`
2. `mvn -q test`
3. Manual smoke through FE:
- Manager CRUD flow
- Local reserve/cancel with budget effect
- Cross-office reservation behavior

Interpretation:
- Placeholder or TODO tests are not behavior proof.
- Duplicate launch/bind conflict is a blocker.

## Cross-Student Handoff Contract
Shared baseline outputs that every student depends on:
- Stable shared config (`PortConfig`, `UDPMessage`, `ReliableUDPSender`)
- Stable startup topology rules for FE/Sequencer/RM/replicas
- Stable A3 guardrail and necessary-change process
- Stable blocker list in `PROJECT2_GROUP_BASE_AUDIT.md`

## Completion Criteria (Shared Gate)
- [ ] Shared checklist items complete
- [ ] Runtime gate complete
- [ ] Any A3 deviation documented with Necessary-Change Note
- [ ] `PROJECT2_GROUP_BASE_AUDIT.md` blockers reviewed and aligned
- [ ] Group sign-off for student role execution
