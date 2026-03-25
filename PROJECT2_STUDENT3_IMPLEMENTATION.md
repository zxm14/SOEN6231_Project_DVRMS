# PROJECT 2 STUDENT3 IMPLEMENTATION (SEQUENCER)

## Role and Purpose
Owner: Student 3

This guide defines Student 3 work for Sequencer across:
- Phase 1 baseline responsibilities
- Phase 2 role extension responsibilities

## Alignment Sources
- `Project.6231w26.txt` (failure-free Sequencer, unique sequence numbers, reliable multicast)
- `FT-DVRMS_Project2_Design_v3.3.docx` (total-order and replay behavior)
- `FT-DVRMS_Project2_Design_v3.3_ALIGNMENT_ADDENDUM.md` (message formats and replica ID contracts)
- `PROJECT2_SHARED_CONFIG_IMPLEMENTATION.md`

## A3 Guardrail (Mandatory)
- Sequencer controls order/reliability only; it must not alter A3 business semantics.
- Any behavior that can affect operation semantics must be justified with a Necessary-Change Note.

## Entry Condition
- [ ] Shared baseline gate in `PROJECT2_SHARED_CONFIG_IMPLEMENTATION.md` is complete.

## Phase 1 Baseline Responsibilities (Sequencer)
Target file: `src/main/java/server/Sequencer.java`

- Receive FE `REQUEST` messages.
- Assign monotonically increasing sequence numbers.
- Reliably multicast `EXECUTE` messages to replicas.
- Maintain history for replay.
- Handle `NACK` and recovered-replica replay (`REPLICA_READY`) paths.

Phase 1 Sequencer done criteria:
- [ ] Request ordering is deterministic and monotonic
- [ ] Replay buffer supports NACK-based resend
- [ ] Recovery replay starts at expected sequence for rejoined replica
- [ ] Reliability path uses shared sender contract

## Phase 2 Extension Responsibilities (Sequencer)
- Harden ACK tracking and replica address update behavior.
- Ensure replay targets correct requester only (NACK path).
- Ensure replay targets recovered replica only (`REPLICA_READY` path).
- Complete Sequencer unit tests in:
  - `src/test/java/unit/Sequencer/SequencerTest.java`

Phase 2 Sequencer done criteria:
- [ ] Sequencer unit tests are enabled and passing
- [ ] Tests cover seq assignment, NACK replay, replica-ready replay
- [ ] Sequencer remains non-blocking enough for concurrent request flow

## Cross-Student Handoff (Inputs/Outputs)
Inputs Student 3 needs:
- FE request contract and correlation fields (Student 1)
- RM recovery ready message contract (`REPLICA_READY`) (Student 2)

Outputs Student 3 provides:
- Stable order/replay behavior for Student 4 integration/failure tests
- Stable sequence+replay expectations for FE and RM coordination

## Traceability Checklist
| Item | Source |
|---|---|
| Sequencer assigns unique sequence numbers | Requirement Project 2 Sequencer bullet |
| Reliable multicast to all replicas | Requirement + design protocol |
| Total-order/replay behavior | Design total-order and recovery sections |
| NACK/READY message handling | Addendum canonical message contracts |
