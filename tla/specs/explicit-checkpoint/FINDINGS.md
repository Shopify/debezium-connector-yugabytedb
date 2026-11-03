# explicit-checkpoint

## Java / spec / run
- **Java**: `YugabyteDBgRPCConnector`, branch `production-vdz.1.9.5.yb.grpc.2026.1.2.0.1` (767ab71): `YugabyteDBStreamingChangeEventSource` (getExplicitCheckpoint, commitOffset, the TABLET_SPLIT wait list, handleTabletSplit), `YugabyteDBConnectorTask.commit` / `getHigherOffsets`.
- **Spec**: `YbExplicitCheckpoint.tla`: one tablet P (records 1..N) splits into C1 and C2 (parent rows by key, then M own records each); one task; crash and restart from cdc_state.
- **Run**: `java -cp ~/.cache/tla/tla2tools-1.7.4.jar tlc2.TLC -config YbExplicitCheckpoint.cfg YbExplicitCheckpoint.tla` (tla2tools 1.7.4, SHA-256 936a2620…).

## Results
| Config | Mutation | Result |
|---|---|---|
| `YbExplicitCheckpoint.cfg` | none (production) | NoGap, ServerBehindCursor and AllDelivered hold (15,626 states, 2 crashes) |
| `.nowait.cfg` | handle the split without waiting for the commit callback | **NoGap violated**: a crash after the split loses the parent's unacked tail, because the children start at the split op |
| `.nocap.cfg` | explicit checkpoint not capped at from_op_id | ServerBehindCursor violated (cdc_state passes the cursor after a restart); no record lost |
| `.commitlower.cfg` | commitOffset may lower the checkpoint | no violation: getHigherOffsets already keeps the highest offset, and a restart starts from cdc_state |

## Conformance
`ExplicitCheckpointConformanceTest` replays every edge of `YbExplicitCheckpoint.conformance.cfg` (2,095 states, 2,127 paths, 34,292 steps, 0.4 s) through the real getExplicitCheckpoint, commitOffset, getHigherOffsets and `OpId.isLesserThanOrEqualTo`. The test plays the server, Connect, and handleTabletSplit's map change (it needs a YBClient). Negative controls: no cap, commitOffset that does nothing, a strict `<` in OpId, and getHigherOffsets without the max all fail; commitOffset that may lower passes (as in the model).

## Notes
- `OpId.isLesserThanOrEqualTo` returns true when *any* of term, index or time is smaller (`||`), not in lexicographic order. With Raft indexes that grow across terms this is likely safe; the model compares indexes only and does not check it.
- Assumed server behavior, to confirm with Yugabyte: the children's cdc_state starts at the split op; GetChanges stores the explicit checkpoint as sent.
