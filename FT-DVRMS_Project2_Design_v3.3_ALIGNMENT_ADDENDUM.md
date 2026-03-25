# FT-DVRMS Project2 Design v3.3 Alignment Addendum

## Purpose
This addendum is the implementation authority for team alignment across:
- `Project.6231w26.txt` (project requirement source)
- `FT-DVRMS_Project2_Design_v3.3.docx` (approved design)
- `PROJECT2_SHARED_CONFIG_IMPLEMENTATION.md`
- `PROJECT2_STUDENT1_IMPLEMENTATION.md`
- `PROJECT2_STUDENT2_IMPLEMENTATION.md`
- `PROJECT2_STUDENT3_IMPLEMENTATION.md`
- `PROJECT2_STUDENT4_IMPLEMENTATION.md`

The `.docx` remains unchanged. This addendum resolves only practical wording/contract drift.

## Canonical Message Contracts
- `REQUEST:reqID:feHost:fePort:op:params`
- `EXECUTE:seqNum:reqID:feHost:fePort:op:params`
- `RESULT:seqNum:reqID:replicaID:resultString`
- `NACK:replicaID:seqStart:seqEnd`
- `INCORRECT_RESULT:reqID:seqNum:replicaID`
- `CRASH_SUSPECT:reqID:seqNum:replicaID`
- `REPLACE_REQUEST:replicaID:reason`
- `VOTE_BYZANTINE:targetReplicaID:voterRmID`
- `VOTE_CRASH:targetReplicaID:ALIVE|CRASH_CONFIRMED:voterRmID`
- `STATE_TRANSFER:sourceReplicaID:snapshot`
- `ACK:INIT_STATE:replicaID:lastSeqNum`
- `REPLICA_READY:replicaID:host:port:lastSeqNum`

## Canonical Replica ID Format
- Use numeric IDs `1`, `2`, `3`, `4` in FE, Sequencer, replicas, RM messages, and guide examples.
- Do not use `R+port` as message `replicaID`.

## Reliability Scope (Minimal, Requirement-Aligned)
- Keep UDP reliability via ACK/NACK and bounded retry with exponential backoff where needed for request ordering/recovery flows.
- Do not introduce extra protocol complexity for this alignment pass.
- FE majority return must stay fast (`f+1 = 2`) and must not be delayed by non-critical notification paths.

## Ownership Boundaries
- Student 1: FE implementation and FE-specific tests.
- Student 2: RM implementation and RM-specific tests.
- Student 3: Sequencer implementation and Sequencer-specific tests.
- Student 4: full integration scenarios `T1–T21` and test client completion.
- Group base: shared contracts, startup consistency, and merge-ready interfaces.

## Non-Goals
- No code refactor requirements are introduced by this addendum.
- No new frameworks/protocols beyond the project requirement and design intent.
