# PROJECT 2 STUDENT4 IMPLEMENTATION (INTEGRATION TESTS)

## Role and Purpose
Owner: Student 4

This guide defines Student 4 work for integration testing and test client behavior across:
- Phase 1 baseline responsibilities
- Phase 2 role extension responsibilities

## Alignment Sources
- `Project.6231w26.txt` (Student 4: proper test cases for all failure situations)
- `FT-DVRMS_Project2_Design_v3.3.docx` (test scenario oracle and failure simulations)
- `FT-DVRMS_Project2_Design_v3.3_ALIGNMENT_ADDENDUM.md` (canonical contracts used in tests)
- `PROJECT2_SHARED_CONFIG_IMPLEMENTATION.md`

## A3 Guardrail (Mandatory)
- Integration tests validate replication and recovery around A3 business logic.
- Test implementation must not redefine business rules; it verifies expected A3-compatible behavior.
- Any expected behavior that differs from A3 must include a Necessary-Change Note.

## Entry Condition
- [ ] Shared baseline gate in `PROJECT2_SHARED_CONFIG_IMPLEMENTATION.md` is complete.

## Phase 1 Baseline Responsibilities (Integration)
Target file: `src/test/java/integration/ReplicationIntegrationTest.java`

- Maintain T1-T21 scaffold with correct scenario names and ordering.
- Ensure startup strategy is consistent with recovery testing (no duplicate direct replica + RM launch in same run).
- Keep at least one end-to-end asserted flow valid through FE->Sequencer->Replica->FE path.

Phase 1 Integration done criteria:
- [ ] Integration startup topology is stable and conflict-free
- [ ] At least one real asserted normal-flow test passes end-to-end
- [ ] Scaffolds are clearly marked as not behavior proof

## Phase 2 Extension Responsibilities (Integration)
- Implement full asserted T1-T21 behaviors.
- Add Byzantine and crash simulation helpers for failure scenarios.
- Verify state consistency after replacements/recovery.
- Confirm both normal and failure paths against expected outcomes.

Phase 2 Integration done criteria:
- [ ] T1-T21 contain real assertions (not TODO-only bodies)
- [ ] Failure simulation paths are deterministic enough for reproducible runs
- [ ] Evidence report summarizes expected vs observed outcomes

## Cross-Student Handoff (Inputs/Outputs)
Inputs Student 4 needs:
- FE final behavior and response contract (Student 1)
- RM replacement and state-transfer behavior (Student 2)
- Sequencer replay/order behavior (Student 3)

Outputs Student 4 provides:
- End-to-end validation evidence for group sign-off
- Reproducible failure scenario coverage for demo readiness

## Traceability Checklist
| Item | Source |
|---|---|
| Student 4 owns all failure-scenario tests | Requirement Project 2 Student 4 bullet |
| Normal and failure scenario definitions | Design test scenario sections |
| Crash/Byzantine simulation approach | Requirement + design failure simulation notes |
| Protocol-consistent assertions | Addendum canonical contracts |
