# Business Model: Community Passenger Air Transport

## Classification
- Repository: `cloud-itonami-5110`
- ISIC Rev.5: `5110` — passenger air transport
- Social impact: aviation safety, regional connectivity, passenger
  rights

## Customer
- independent/regional air carriers needing an auditable
  certification and operations platform
- charter operators needing verifiable flight-dispatch records
- regulators needing verifiable certificate-scope and maintenance
  records
- programs that cannot accept closed, unauditable airline-management
  platforms

## Offer
- Air Operator Certificate scope management
- robotics-assisted ground handling, baggage handling and maintenance
  inspection
- booking and flight-dispatch records
- reconciliation and disclosure records
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per aircraft/route
- support retainer with SLA
- ground-handling/maintenance robot integration and maintenance

## Trust Controls
- a robot action the governor refuses is never dispatched
- safety-critical actions (flight dispatch outside verified
  certificate scope, maintenance release without inspection) require
  human sign-off
- a flight cannot be dispatched outside its verified certificate scope
- reconciliation records require verified evidence
- sensitive passenger and operations data stays outside Git
