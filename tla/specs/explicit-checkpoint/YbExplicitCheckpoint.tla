------------------------- MODULE YbExplicitCheckpoint -------------------------
(***************************************************************************)
(* Explicit checkpointing in the production Yugabyte connector             *)
(* (YugabyteDBgRPCConnector, branch production-vdz.1.9.5.yb.grpc.2026.1.2.0.1) *)
(* for one tablet P that splits into C1 and C2.                            *)
(*                                                                         *)
(* Java (YugabyteDBStreamingChangeEventSource = SCES):                     *)
(*  - GetChanges sends explicit_checkpoint = getExplicitCheckpoint(p, from)*)
(*    = from_op_id if from_op_id <= tabletToExplicitCheckpoint[p], else    *)
(*    tabletToExplicitCheckpoint[p] (SCES:933-945).                        *)
(*  - YugabyteDBConnectorTask.commit reads the committed Connect offsets,  *)
(*    keeps the highest OpId per tablet (getHigherOffsets) and calls       *)
(*    commitOffset, which raises tabletToExplicitCheckpoint and never      *)
(*    lowers it by index (SCES:1068-1115).                                 *)
(*  - TABLET_SPLIT from GetChanges: handle the split now if the last       *)
(*    record's checkpoint <= the explicit checkpoint sent, else put the    *)
(*    tablet on the wait list until a commit catches up, then              *)
(*    setCheckpointWithGetChanges and handle it (SCES:487-520, 575-605).   *)
(*  - handleTabletSplit: drop the parent, add the children with the        *)
(*    checkpoint the server returns (SCES:1194-1286).                      *)
(*  - Task start: poll from the cdc_state checkpoints that                 *)
(*    GetTabletListToPollForCDC returns (SCES:380-420), not from Kafka.    *)
(* Server: GetChanges stores explicit_checkpoint in cdc_state. The         *)
(* children's cdc_state starts at the split op S.                          *)
(* Abstractions: an OpId is its index (one term); one record per op; one   *)
(* task; Kafka acks in order; each record carries only its tablet's offset *)
(* (YugabyteDBOffsetContext.getOffset(partition)).                         *)
(***************************************************************************)
EXTENDS Integers, Sequences, FiniteSets

CONSTANTS
    N,           \* parent records before the split (indexes 1..N)
    M,           \* records of each child after the split (indexes S+1..S+M)
    MaxCrashes,
    Mutation     \* "none" | "noWait" | "noCap" | "commitLower"

ASSUME Mutation \in {"none", "noWait", "noCap", "commitLower"}

P == "P"
Children == {"C1", "C2"}
Tablets == {P} \cup Children
S == N + 1           \* the SPLIT_OP index
None == -1

\* The record a tablet returns at index k (parent rows keep their identity).
Key(k) == IF k % 2 = 1 THEN "C1" ELSE "C2"
HasRec(t, k) ==
    IF t = P THEN k \in 1..N
    ELSE (k \in 1..N /\ Key(k) = t) \/ k \in (S + 1)..(S + M)
Id(t, k) == IF k <= N THEN <<"row", k>> ELSE <<t, k>>
Ids == {Id(P, k) : k \in 1..N} \cup {Id(c, k) : c \in Children, k \in (S + 1)..(S + M)}

\* The next record after from, or None.
NextRec(t, from) ==
    LET ks == {k \in (from + 1)..(S + M) : HasRec(t, k)}
    IN IF ks = {} THEN None ELSE CHOOSE k \in ks : \A j \in ks : k <= j

VARIABLES
    assigned,   \* tablets the task polls (tabletPairList)
    cursor,     \* from_op_id per tablet
    explicit,   \* tabletToExplicitCheckpoint (None = no entry)
    lastRec,    \* lastRecordCheckpoint per tablet (None = null)
    waiting,    \* P is in splitTabletsWaitingForCallback
    srv,        \* cdc_state checkpoint (None = no entry)
    sent,       \* records handed to the producer, across runs: <<t, k>>
    acked,      \* acked prefix of sent
    stored,     \* Connect offset store: last acked index per tablet
    high,       \* YugabyteDBConnectorTask.ybOffset (in memory, per task instance)
    crashes,
    delivered   \* ghost: record ids durable in Kafka

vars == <<assigned, cursor, explicit, lastRec, waiting, srv, sent, acked, stored, high, crashes, delivered>>

NoneMap == [t \in Tablets |-> None]

Init ==
    /\ assigned = {P}
    /\ cursor = [NoneMap EXCEPT ![P] = 0]
    /\ explicit = [NoneMap EXCEPT ![P] = 0]
    /\ lastRec = NoneMap
    /\ waiting = FALSE
    /\ srv = [NoneMap EXCEPT ![P] = 0]
    /\ sent = << >> /\ acked = 0
    /\ stored = NoneMap /\ high = NoneMap
    /\ crashes = 0 /\ delivered = {}

\* getExplicitCheckpoint(p, from_op_id)
Ex(t) == IF Mutation = "noCap" THEN explicit[t]
         ELSE IF cursor[t] <= explicit[t] THEN cursor[t] ELSE explicit[t]

\* handleTabletSplit with the children's cdc_state at S.
SplitVars(srvP) ==
    /\ assigned' = Children
    /\ srv' = [t \in Tablets |-> IF t = P THEN srvP ELSE S]
    /\ cursor' = [t \in Tablets |-> IF t = P THEN None ELSE S]
    /\ explicit' = [t \in Tablets |-> IF t = P THEN None ELSE S]
    /\ lastRec' = [t \in Tablets |-> IF t = P THEN lastRec[P] ELSE None]
    /\ waiting' = FALSE

GetChanges(t) ==
    /\ t \in assigned /\ ~(t = P /\ waiting)
    /\ LET ex == Ex(t)
           k  == NextRec(t, cursor[t])
       IN IF k # None
          THEN /\ sent' = Append(sent, <<t, k>>)
               /\ cursor' = [cursor EXCEPT ![t] = k]
               /\ lastRec' = [lastRec EXCEPT ![t] = k]
               /\ srv' = [srv EXCEPT ![t] = ex]
               /\ UNCHANGED <<assigned, explicit, waiting>>
          ELSE IF t = P
          THEN \* TABLET_SPLIT
               IF Mutation = "noWait" \/ lastRec[P] = None \/ lastRec[P] <= ex
               THEN /\ SplitVars(ex) /\ UNCHANGED sent
               ELSE /\ waiting' = TRUE
                    /\ srv' = [srv EXCEPT ![P] = ex]
                    /\ UNCHANGED <<assigned, cursor, explicit, lastRec, sent>>
          ELSE \* no more records on a child: an empty response
               /\ srv' = [srv EXCEPT ![t] = ex]
               /\ UNCHANGED <<assigned, cursor, explicit, lastRec, waiting, sent>>
    /\ UNCHANGED <<acked, stored, high, crashes, delivered>>

\* The wait list: a commit has caught up, so set the checkpoint and split.
SplitCallback ==
    /\ waiting /\ explicit[P] # None /\ (lastRec[P] = None \/ lastRec[P] <= explicit[P])
    /\ SplitVars(explicit[P])
    /\ UNCHANGED <<sent, acked, stored, high, crashes, delivered>>

Ack ==
    /\ acked < Len(sent)
    /\ acked' = acked + 1
    /\ delivered' = delivered \cup {Id(sent[acked + 1][1], sent[acked + 1][2])}
    /\ UNCHANGED <<assigned, cursor, explicit, lastRec, waiting, srv, sent, stored, high, crashes>>

\* Connect offset flush: the offset of the last acked record of each tablet.
LastAcked(t) ==
    LET is == {i \in 1..acked : sent[i][1] = t}
    IN IF is = {} THEN None ELSE sent[CHOOSE i \in is : \A j \in is : i >= j][2]

Flush ==
    /\ \E t \in Tablets : LastAcked(t) # None /\ LastAcked(t) # stored[t]
    /\ stored' = [t \in Tablets |-> IF LastAcked(t) # None THEN LastAcked(t) ELSE stored[t]]
    /\ UNCHANGED <<assigned, cursor, explicit, lastRec, waiting, srv, sent, acked, high, crashes, delivered>>

\* YugabyteDBConnectorTask.commit -> commitOffset(ybOffset).
Commit ==
    /\ \E t \in Tablets : stored[t] # None
    /\ LET h == [t \in Tablets |-> IF stored[t] > high[t] THEN stored[t] ELSE high[t]]
       IN /\ high' = h
          /\ explicit' = [t \in Tablets |->
                IF h[t] = None THEN explicit[t]
                ELSE IF explicit[t] # None /\ h[t] < explicit[t] /\ Mutation # "commitLower"
                     THEN explicit[t] ELSE h[t]]
          /\ explicit' # explicit
    /\ UNCHANGED <<assigned, cursor, lastRec, waiting, srv, sent, acked, stored, crashes, delivered>>

\* Crash and task restart: unacked records are lost; poll from cdc_state.
Restart ==
    /\ crashes < MaxCrashes
    /\ LET ts == IF srv["C1"] # None THEN Children ELSE {P}
       IN /\ assigned' = ts
          /\ cursor' = [t \in Tablets |-> IF t \in ts THEN srv[t] ELSE None]
          /\ explicit' = [t \in Tablets |-> IF t \in ts THEN srv[t] ELSE None]
    /\ lastRec' = NoneMap /\ waiting' = FALSE /\ high' = NoneMap
    /\ sent' = SubSeq(sent, 1, acked)
    /\ crashes' = crashes + 1
    /\ UNCHANGED <<srv, acked, stored, delivered>>

Next ==
    \/ \E t \in Tablets : GetChanges(t)
    \/ SplitCallback \/ Ack \/ Flush \/ Commit \/ Restart

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
(* Properties *)

\* At-least-once: a restart from cdc_state never skips a record that is not in Kafka.
NoGap == \A t \in Tablets : srv[t] # None =>
            \A k \in 1..srv[t] : HasRec(t, k) => Id(t, k) \in delivered

\* The server checkpoint of a polled tablet never passes the connector's cursor.
ServerBehindCursor == \A t \in assigned : srv[t] <= cursor[t]

\* Every record reaches Kafka once the task has read everything and drained.
Drained == assigned = Children /\ acked = Len(sent)
              /\ \A c \in Children : NextRec(c, cursor[c]) = None
AllDelivered == Drained => Ids \subseteq delivered
=============================================================================
