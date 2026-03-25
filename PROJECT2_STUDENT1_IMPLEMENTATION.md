# PROJECT 2 STUDENT1 IMPLEMENTATION (FE)

## Role and Purpose
Owner: Student 1

This guide defines Student 1 work for Front End (FE) across:
- Phase 1 baseline responsibilities (required for group readiness)
- Phase 2 role extension responsibilities (individual completion)

## Alignment Sources
- `Project.6231w26.txt` (FE receives client request, forwards to Sequencer, returns correct result)
- `FT-DVRMS_Project2_Design_v3.3.docx` (FE voting and fault reporting behavior)
- `FT-DVRMS_Project2_Design_v3.3_ALIGNMENT_ADDENDUM.md` (canonical message contracts and replica IDs)
- `PROJECT2_SHARED_CONFIG_IMPLEMENTATION.md` (shared gate dependency)

## A3 Guardrail (Mandatory)
- Keep A3 client-facing behavior semantics intact.
- FE changes should orchestrate replication behavior, not alter business rules.
- Any non-A3 behavior must include a Necessary-Change Note (source clause + impact + validation evidence).

## Entry Condition
- [ ] Shared baseline gate in `PROJECT2_SHARED_CONFIG_IMPLEMENTATION.md` is complete.

## Phase 1 Baseline Responsibilities (FE)
Target file: `src/main/java/server/FrontEnd.java`

- Preserve A3 SOAP method signatures.
- Build `REQUEST` messages and send to Sequencer.
- Collect `RESULT` responses and return as soon as `f+1 = 2` matching responses exist.
- Report mismatches/timeouts to RMs (`INCORRECT_RESULT`, `CRASH_SUSPECT`, `REPLACE_REQUEST`).
- Keep canonical numeric replica IDs (`1..4`) in FE/RM fault notifications.
- Ensure find payload contract aligns with parity target (`FIND:<customerID>:<vehicleType>`).

Phase 1 FE done criteria:
- [ ] FE publishes endpoint and accepts SOAP calls
- [ ] FE forwards requests to Sequencer via shared contract
- [ ] FE majority logic returns correct response (`f+1=2`)
- [ ] FE fault reporting hooks are active and correctly formatted

## Phase 2 Extension Responsibilities (FE)
- Harden timeout and majority behavior under mixed crash+Byzantine responses.
- Finalize FE-side correlation and response handling for all operations.
- Complete FE unit tests and remove disabled placeholders in:
  - `src/test/java/unit/FrontEnd/FrontEndTest.java`

Phase 2 FE done criteria:
- [ ] FE unit tests are enabled (no role-owned disabled placeholders)
- [ ] FE tests cover majority, mismatch counter, replace trigger, crash suspect reporting
- [ ] FE behavior remains compatible with shared contracts and audit gate

## Cross-Student Handoff (Inputs/Outputs)
Inputs Student 1 needs:
- Shared message/port contracts from shared guide
- Sequencer `EXECUTE`/`RESULT` reliability expectations

Outputs Student 1 provides:
- Stable FE request/response behavior for Student 4 integration tests
- Clear FE fault notification behavior for Student 2 RM validation

## Traceability Checklist
| Item | Source |
|---|---|
| FE as single client entry point | Requirement Project 2 FE bullet |
| FE returns correct result on `f+1` | Design voting and fault tolerance rationale |
| FE fault reporting to RM | Requirement/design FE+RM coordination |
| Canonical message/replicaID format | Addendum canonical contracts |
