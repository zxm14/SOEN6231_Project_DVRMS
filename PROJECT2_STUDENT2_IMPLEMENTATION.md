# PROJECT 2 STUDENT2 IMPLEMENTATION (RM)

## Role and Purpose
Owner: Student 2

This guide defines Student 2 work for Replica Manager (RM) across:
- Phase 1 baseline responsibilities
- Phase 2 role extension responsibilities

## Alignment Sources
- `Project.6231w26.txt` (RM failure detection/recovery responsibilities)
- `FT-DVRMS_Project2_Design_v3.3.docx` (vote, replacement, state transfer, readiness flow)
- `FT-DVRMS_Project2_Design_v3.3_ALIGNMENT_ADDENDUM.md` (canonical recovery message contracts)
- `PROJECT2_SHARED_CONFIG_IMPLEMENTATION.md`

## A3 Guardrail (Mandatory)
- RM behavior must preserve A3 business outcomes by restoring/maintaining replica state consistency.
- RM must not introduce business-rule rewrites.
- Any required deviation must include a Necessary-Change Note.

## Entry Condition
- [ ] Shared baseline gate in `PROJECT2_SHARED_CONFIG_IMPLEMENTATION.md` is complete.

## Phase 1 Baseline Responsibilities (RM)
Target file: `src/main/java/server/ReplicaManager.java`

- Launch and monitor co-located replica.
- Perform heartbeat checks and crash suspicion handling.
- Participate in vote flow for Byzantine/crash replacement decisions.
- Replace faulty replica and coordinate state restoration.
- Broadcast readiness (`REPLICA_READY`) after replacement.

Phase 1 RM done criteria:
- [ ] Replica lifecycle management works (launch/kill/relaunch)
- [ ] Heartbeat path works against suspected replica
- [ ] Vote path works with strict majority of reachable RMs
- [ ] State transfer initialization flow succeeds for replacement replica

## Phase 2 Extension Responsibilities (RM)
- Harden vote-window behavior and malformed message handling.
- Finalize replacement safety around concurrent fault reports.
- Ensure replacement readiness and replay handoff stays consistent.
- Maintain/extend RM unit tests in:
  - `src/test/java/unit/ReplicaManager/*`

Phase 2 RM done criteria:
- [ ] RM unit tests remain enabled and passing
- [ ] Behavior tests cover vote, replacement, and state transfer paths
- [ ] RM output contracts are stable for Sequencer/Integration dependencies

## Cross-Student Handoff (Inputs/Outputs)
Inputs Student 2 needs:
- FE fault notification message contracts (Student 1)
- Replica readiness replay expectation (Student 3)

Outputs Student 2 provides:
- Stable replacement + readiness behavior for Student 3 replay logic
- Reliable crash/byzantine recovery behavior for Student 4 failure tests

## Traceability Checklist
| Item | Source |
|---|---|
| RM detects/replaces failed replica | Requirement Project 2 RM bullet |
| RM crash vote and byzantine vote | Design recovery sections |
| RM state transfer and readiness message | Design recovery + addendum contracts |
| Reachable-majority handling | Design crash/byzantine consensus rationale |
