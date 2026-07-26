# cloud-itonami-5110

Open Business Blueprint for **ISIC Rev.5 5110**: passenger air
transport (scheduled and charter air-carrier operations).

This repository designs a forkable OSS business for community
passenger air transport: air-operator-certificate scope management,
robotics-assisted ground handling and maintenance, and booking/
reconciliation records — run by a qualified operator so an air carrier
keeps its own certification and flight-operations history instead of
renting a closed airline-management platform.

## The six operations

| op | what it drafts | ever auto-commits? |
|---|---|---|
| `:log-flight-record` | block time, on-time performance, passenger counts | yes, at phase 3, when governor-clean |
| `:schedule-flight-operation` | gate/crew scheduling coordination | never -- always a human |
| `:coordinate-maintenance` | a maintenance slot, parts, a technician | never -- always a human |
| `:flag-flight-safety-concern` | escalation of a safety finding | **never, at any phase** |
| `:quote-fare` | a fare quote off the flight's own filed rate plan | never -- always a human |
| `:place-booking` | a seat **hold** against the flight's own inventory | never -- always a human |

The first four are the OPERATIONS surface and are writable from phase 1.
The last two are the COMMERCIAL surface and only become writable at
**phase 4** -- deliberately after the operations ops, so an operator can
run this actor's ops surface without ever turning on its commercial
surface.

`:place-booking` places a hold, never a sale. Converting a hold into a
sold seat is the operator's ticketing system's act and is outside this
actor's vocabulary -- `airlineops.store` never calls
`kotoba.reservation/confirm-hold`.

## The governor recomputes; it does not take the advisor's word

Six of the governor's eight hard checks are the shape every sibling
actor in this fleet uses: closed op allowlist, propose-only effect,
finalize-authority scope drift, spec-basis citation, certification
verification, open-concern blocking.

Two are specific to the commercial surface and are the reason this
vertical depends on
[`kotoba-lang/reservation`](https://github.com/kotoba-lang/reservation):

- **fare mismatch** -- the governor re-runs
  `kotoba.reservation/quote-for` against the flight's own filed rate
  plan and rejects a stated total that does not match. An advisor can
  state a number; it cannot be trusted to have done the arithmetic.
- **oversell** -- the governor re-runs
  `kotoba.reservation/availability-supports?` against the flight's own
  seat bucket and rejects a booking for a seat that does not exist.

Both are **ground-truth recomputes**, not restatements. A check that
cannot be performed -- no rate plan, no request, no stated total, no
bucket -- is itself a HARD violation: this governor does not assume
compliance when it is structurally unable to verify it. `reservation`
is integer-only and clock-free precisely so the recompute is
bit-identical to the advisor's own computation.

The demo (`clojure -M:dev:run`) drives an advisor that deliberately
states a fare it did not compute, and shows it being held.

Commercial finalization is out of scope on the same footing as
operational finalization: issuing a ticket, capturing payment, filing a
fare and confirming a seat as sold are HARD, un-overridable blocks.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (ground handling,
baggage handling, maintenance inspection) operate under an actor that
proposes actions and an independent **Aviation Safety Governor** that
gates them. The governor never dispatches a flight itself; `:high`/
`:safety-critical` actions (any flight dispatch outside the carrier's
own verified Air Operator Certificate scope, any maintenance release
that has not passed inspection) require human sign-off.

## Core Contract

```text
intake + identity + certificate scope + booking
        |
        v
Airline Operations Advisor -> Aviation Safety Governor -> certificate record, dispatch, reconciliation record, or human approval
        |
        v
robot actions (gated) + flight/maintenance record + reconciliation record + audit ledger
```

No automated advice can dispatch a flight the governor refuses, approve
a maintenance release outside its verified inspection scope, or publish
a reconciliation record without governor approval and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `5110`). Implemented by:

- [`kotoba-lang/reservation`](https://github.com/kotoba-lang/reservation) — seat inventory, holds, rate plans, quotes, and the recompute seam
- [`kotoba-lang/langgraph`](https://github.com/kotoba-lang/langgraph) — the supervised StateGraph runtime
- [`kotoba-lang/logistics`](https://github.com/kotoba-lang/logistics) — booking, transit, delivery/reconciliation contracts
- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs (optional; the physical layer is not implemented in this R0)

## Run it

```bash
clojure -M:lint        # clj-kondo, errors fail
clojure -M:dev:test    # 60 tests, 272 assertions
clojure -M:dev:run     # the demo: clean path + every HARD-hold scenario
```

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
