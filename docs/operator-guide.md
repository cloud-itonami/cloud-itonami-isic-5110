# Operator Guide

## First Deployment
1. Register operator, aircraft/routes, certificate scope, staff and
   robots.
2. Import existing booking and flight-dispatch history.
3. Run read-only certificate-scope and ground-handling/maintenance
   robot mission dry-runs.
4. Configure safety-class allowed sets and human sign-off paths.
5. Publish a dry-run reconciliation record and audit export.

## Minimum Production Controls
- certificate-scope validation before any dispatch
- governor gate on every robot action before dispatch
- human sign-off for :high/:safety-critical actions (out-of-
  certificate dispatch, uninspected maintenance release)
- evidence-backed reconciliation records
- audit export for every dispatch, sign-off and reconciliation
- backup manual flight-operations process

## Certification
Certified operators must prove robot-safety integrity, certificate-
scope discipline, evidence-backed reconciliation records and human
review for dispatch-affecting actions.
