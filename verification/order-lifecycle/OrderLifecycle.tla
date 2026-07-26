--------------------------- MODULE OrderLifecycle ---------------------------
\* One-order lifecycle model for the cancellation/execution race.
\*
\* RequestCancel and ConfirmCancel are deliberately separate. While a cancel
\* request is pending, either ConfirmCancel or ApplyFill may win the race.

EXTENDS Integers, Naturals

CONSTANT Quantity

ASSUME Quantity \in Nat \ {0}

Statuses ==
    {"NEW", "LIVE", "PARTIALLY_FILLED", "FILLED", "CANCELLED", "REJECTED"}

ActiveStatuses == {"LIVE", "PARTIALLY_FILLED"}
TerminalStatuses == {"FILLED", "CANCELLED", "REJECTED"}

VARIABLES status, filled, cancelPending

vars == <<status, filled, cancelPending>>

Remaining == Quantity - filled

Init ==
    /\ status = "NEW"
    /\ filled = 0
    /\ cancelPending = FALSE

Accept ==
    /\ status = "NEW"
    /\ status' = "LIVE"
    /\ UNCHANGED <<filled, cancelPending>>

Reject ==
    /\ status = "NEW"
    /\ status' = "REJECTED"
    /\ UNCHANGED <<filled, cancelPending>>

RequestCancel ==
    /\ status \in ActiveStatuses
    /\ ~cancelPending
    /\ cancelPending' = TRUE
    /\ UNCHANGED <<status, filled>>

ConfirmCancel ==
    /\ status \in ActiveStatuses
    /\ cancelPending
    /\ status' = "CANCELLED"
    /\ cancelPending' = FALSE
    /\ UNCHANGED filled

ApplyFill(amount) ==
    /\ status \in ActiveStatuses
    /\ amount \in 1..Remaining
    /\ filled' = filled + amount
    /\ status' =
        IF filled' = Quantity
        THEN "FILLED"
        ELSE "PARTIALLY_FILLED"
    /\ UNCHANGED cancelPending

\* A full fill can beat a pending cancel. The cancel request then resolves
\* without changing the terminal FILLED state.
DeclineCancelAfterFill ==
    /\ status = "FILLED"
    /\ cancelPending
    /\ cancelPending' = FALSE
    /\ UNCHANGED <<status, filled>>

Next ==
    \/ Accept
    \/ Reject
    \/ RequestCancel
    \/ ConfirmCancel
    \/ \E amount \in 1..Remaining : ApplyFill(amount)
    \/ DeclineCancelAfterFill

\* Fairness applies only to resolving a pending cancellation. It does not force
\* an order to be accepted, filled, rejected, or cancelled.
Spec ==
    /\ Init
    /\ [][Next]_vars
    /\ WF_vars(ConfirmCancel)
    /\ WF_vars(DeclineCancelAfterFill)

TypeOK ==
    /\ status \in Statuses
    /\ filled \in 0..Quantity
    /\ cancelPending \in BOOLEAN

FilledBounds ==
    /\ filled >= 0
    /\ filled <= Quantity
    /\ Remaining = Quantity - filled
    /\ Remaining >= 0

StatusMatchesFill ==
    /\ (status \in {"NEW", "LIVE", "REJECTED"} => filled = 0)
    /\ (status = "PARTIALLY_FILLED" => filled > 0 /\ filled < Quantity)
    /\ (status = "FILLED" => filled = Quantity)
    /\ (status = "CANCELLED" => filled < Quantity)

CancelRequestConsistent ==
    cancelPending => status \in (ActiveStatuses \cup {"FILLED"})

\* Once a terminal status is reached, later steps cannot change it.
TerminalStatusNeverChanges ==
    [][(status \in TerminalStatuses) => status' = status]_vars

\* A confirmed cancellation freezes the executed quantity.
NoExecutionAfterCancellation ==
    [][(status = "CANCELLED") => filled' = filled]_vars

\* If a cancel is requested, confirmation or a winning full fill must
\* eventually clear the request.
CancelRequestEventuallyResolves ==
    cancelPending ~> ~cancelPending

=============================================================================
