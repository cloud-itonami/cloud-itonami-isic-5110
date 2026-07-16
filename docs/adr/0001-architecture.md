# ADR-0001: AirlineOps-LLM ⊣ Aviation Safety Governor architecture

## Status

Accepted. `cloud-itonami-isic-5110` promoted from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

`cloud-itonami-isic-5110` was registered as a `:blueprint`-tier repo
(README, business-model, operator-guide, `blueprint.edn` published,
GitHub repo created) but had no `deps.edn`, `src`, or `test` -- an
early bulk-scaffolding-pass entry, not yet promoted to real code. This
ADR records the governed-actor architecture that promotes it, following
the same langgraph StateGraph + independent Governor + Phase 0->3
rollout pattern established by `cloud-itonami-isic-6511` (life
insurance) and applied across many prior siblings, most recently
`cloud-itonami-isic-4920` (community freight transport).

## Decision

### Decision 1: a passenger-airline OPERATIONS COORDINATION actor, not a flight-safety authority

This actor's closed op-allowlist is exactly four ops, all
`:effect :propose`: `:log-flight-record` (flight/passenger-manifest/
on-time-performance logging), `:schedule-flight-operation` (gate/crew
scheduling coordination), `:flag-flight-safety-concern` (surface a
mechanical-fault/weather/go-no-go concern to a human), and
`:coordinate-maintenance` (maintenance scheduling coordination -- not
a maintenance RELEASE/return-to-service sign-off). It never clears an
aircraft for departure and never overrides a weather/go-no-go hold --
those acts are structurally outside this actor's vocabulary.

This is a narrower scope than the originally-published
`README.md`/`docs/business-model.md` text (which describes a broader
"booking/dispatch/reconciliation" vision, `:operating-states
[:intake :book :transit :deliver :reconcile :audit]` in the
`kotoba-lang/industry` registry entry). Per this fleet's own "extending
coverage is additive, scope down for R0" convention (see
`cloud-itonami-isic-4920`'s own ADR-0001 Decision 10), this build
deliberately implements the operations-coordination slice of that
vision now and leaves booking/reconciliation as a follow-up. The
original README/business-model/operator-guide docs are left
UNMODIFIED (they are the blueprint-tier vision, not a contract this R0
build must literally match field-for-field); this ADR is the
authoritative record of what the R0 implementation actually covers.

### Decision 2: `:effect` is structurally ALWAYS `:propose`

Unlike `cloud-itonami-isic-4920`'s own `:shipment/upsert`/`:shipment/
mark-dispatched`-style varying effects, every proposal this actor's
advisor can produce carries a literal `:effect :propose` -- this actor
never performs a real-world actuation of any kind, only ever drafts
and appends a coordination record. `airlineops.governor`'s
`effect-not-propose-violations` check independently re-verifies this
on every proposal (defense in depth: even if the advisor drifted, the
governor still blocks a non-`:propose` effect), and
`airlineops.store/commit-record!` itself only recognizes `:propose`.

### Decision 3: `finalize-authority-scope-violations` -- a HARD, PERMANENT block, phrased as the finalization ACTION

Per the fleet-wide known bug class (multiple sibling actors have
independently hit and fixed the SAME mistake: a scope-exclusion term
list phrased as a bare noun accidentally matches inside the mock
advisor's own default rationale/disclaimer text for a legitimate
proposal, causing the actor to self-block on its own happy path),
`airlineops.governor/finalize-authority-phrases` is phrased as the
finalization/execution ACTION ("finalize the go-no-go decision",
"clear the aircraft for departure", "override the weather hold
decision") rather than a bare topic noun ("safety"/"weather"/
"go-no-go" alone). `airlineops.airlineopsllm/propose-flag-flight-
safety-concern` legitimately talks about mechanical faults and weather
as the CONTENT of a concern being flagged -- a bare-noun term list
would have self-tripped on exactly this op's own happy path.
`test/airlineops/governor_contract_test.clj`'s
`default-advisor-proposals-never-self-trip-finalize-authority-scope`
asserts directly, for all four ops, that the default mock advisor's
own proposals never trip this check.

### Decision 4: `:flag-flight-safety-concern` is doubly enforced to never auto-commit

Two independent layers agree: `airlineops.governor`'s `high-stakes`
gate (keyed on the proposal's own `:stake :coordination/flag-safety-
concern`) always forces `:escalate?` true regardless of confidence or
other checks being clean, AND `airlineops.phase`'s phase table never
adds `:flag-flight-safety-concern` to any phase's `:auto` set,
including phase 3 -- a permanent structural fact, not a rollout
milestone still to come (mirroring `cloud-itonami-isic-4920`'s own
dual-actuation-never-auto pattern for `:shipment/dispatch`/
`:consignment/settle`).

### Decision 5: `certification-unverified-violations` -- ground truth this actor CONSUMES, never MINTS

`:certification-verified?` represents a flight's own aircraft/Air-
Operator-Certificate record, independently verified and registered by
a real civil aviation authority OUTSIDE this actor's own closed
op-allowlist. None of the four ops ever sets it -- there is no
`:jurisdiction/assess`-style op in this actor's vocabulary (unlike
`cloud-itonami-isic-4920`'s own `:jurisdiction/assess`). This is
evaluated UNCONDITIONALLY across all four ops: no coordination
proposal may proceed for a flight whose own certification has not been
independently verified and registered.

### Decision 6: `open-safety-concern-blocks-op` -- a genuinely new check, exempting the flag op itself

An unresolved flight-safety concern already on file
(`:safety-concern-raised? true` AND `:safety-concern-resolved? false`)
blocks `:log-flight-record`/`:schedule-flight-operation`/`:coordinate-
maintenance` on that flight, but deliberately NOT `:flag-flight-
safety-concern` itself -- the safety-reporting channel must always stay
open, including to report further detail on an already-open concern.
Resolving a concern (`:safety-concern-resolved? true`) is likewise
OUTSIDE this actor's own op-allowlist -- a real flight-safety
authority's call, not this actor's, mirroring Decision 5's "consume,
never mint" discipline for ground-truth safety facts.

### Decision 7: self-contained, no bespoke domain capability library

There is no `kotoba-lang/aviation` bespoke domain capability library to
delegate airworthiness/AOC validation to (checked: `kotoba-lang` org
has no aviation-domain package). Like `cloud-itonami-isic-5020`
(marine tanker) and `cloud-itonami-isic-5210` (warehousing), this R0
build is self-contained: `airlineops.facts`/`airlineops.registry`/
`airlineops.governor` implement the domain logic as pure functions
rather than wrapping an external lib.

### Decision 8: `blueprint.edn` maturity flip

`blueprint.edn` did not yet carry `:itonami.blueprint/maturity` (its
`:required-technologies`/`:optional-technologies` already correctly
matched the `kotoba-lang/industry` registry entry, so no field-sync fix
was needed there) -- fixed by adding `:itonami.blueprint/maturity
:implemented`, matching the registry's own `:maturity :implemented`
flip.

## Alternatives considered

- **Matching the originally-published booking/dispatch/reconciliation
  vision literally, op-for-op.** Rejected for R0: the task at hand
  specifies a narrower, explicit four-op operations-coordination
  vocabulary with a clear non-actuation invariant; scoping down and
  recording the gap here (Decision 1) follows this fleet's own
  established "extending coverage is additive" convention rather than
  inventing an op set the governor rules were not designed against.
- **A bare-noun scope-exclusion term list** (`#{"safety" "weather"
  "go-no-go"}`). Rejected: this is the exact fleet-wide known bug class
  (Decision 3) -- it would have self-tripped on
  `propose-flag-flight-safety-concern`'s own legitimate happy path.
- **Rewriting the existing README/business-model/operator-guide docs
  to match the R0 scope exactly.** Rejected: the task instructions
  direct keeping the existing boilerplate docs rather than recreating
  the repo; this ADR is the authoritative record of the gap instead.

## Consequences

- `cloud-itonami-isic-5110` promoted to `:implemented`.
- Establishes a "ground truth consumed, never minted" pattern for both
  certification-verification and safety-concern-resolution facts --
  this actor coordinates around them but never sets them itself.
- `MemStore` ‖ `DatomicStore` parity is proven by
  `test/airlineops/store_contract_test.clj`.
- The self-tripping-bug-class regression is covered by a dedicated
  test, not just avoided by convention.

## References

- `cloud-itonami-isic-6511/docs/adr/0001-architecture.md` (origin of
  the general governed-actor architecture pattern)
- `cloud-itonami-isic-4920/docs/adr/0001-architecture.md` (origin of
  the dual-actuation-never-auto pattern this build's
  `:flag-flight-safety-concern` treatment mirrors)
- 14 C.F.R. Part 121 (US, Operating Requirements: Domestic, Flag, and
  Supplemental Operations)
- 航空法 (Civil Aeronautics Act, Japan)
- Air Navigation Order 2016 (SI 2016/765, UK)
- Luftverkehrsgesetz (LuftVG) / EU Regulation 965/2012 Air OPS (Germany)
