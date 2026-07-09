# cloud-itonami-5110

Open Business Blueprint for **ISIC Rev.5 5110**: passenger air
transport (scheduled and charter air-carrier operations).

This repository designs a forkable OSS business for community
passenger air transport: air-operator-certificate scope management,
robotics-assisted ground handling and maintenance, and booking/
reconciliation records — run by a qualified operator so an air carrier
keeps its own certification and flight-operations history instead of
renting a closed airline-management platform.

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

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/logistics`](https://github.com/kotoba-lang/logistics) — booking, transit, delivery/reconciliation contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
