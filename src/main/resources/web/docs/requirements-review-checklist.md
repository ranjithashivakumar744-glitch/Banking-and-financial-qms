# Requirement Review Checklist

**Record:** Copy for each SRS / baseline review · **Artifact revision:** ______ · **Date:** ______

## Business and source
- [ ] Provider, source, user/actor, business objective, and decision authority are identified.
- [ ] Each requirement is necessary, in scope, uniquely identified, and free of duplicate/conflicting statements.
- [ ] Priority, owner, assumptions, dependencies, constraints, and risk are explicit.
- [ ] Customer terminology and operational workflow have been validated.

## Quality and testability
- [ ] One clear outcome per requirement; no vague terms such as “fast,” “user-friendly,” or “as needed.”
- [ ] Acceptance criteria have measurable values, units, timing, boundaries, and expected result.
- [ ] Positive, invalid, boundary, failure, recovery, and retry cases are considered.
- [ ] Test method, test data, responsible owner, and evidence are feasible.
- [ ] Performance, reliability, security/privacy, audit, compatibility, and maintainability needs are explicit where relevant.

## Banking rules and interfaces
- [ ] Account, amount, currency and transfer inputs: validation, precision, required fields and error responses are defined.
- [ ] Business rules (interest, fees, EMI, limits) have formula, rounding mode, boundary values and worked examples approved by the Customer.
- [ ] Transfer idempotency, concurrency and double-posting prevention are specified.
- [ ] Transaction states (pending, posted, reversed, failed), ordering and invalid transitions are specified.
- [ ] External interfaces (payment, core ledger, alert gateway): schema, version, timeout, retry budget and degraded-mode behavior are specified.
- [ ] Alert trigger, deduplication, delivery deadline and failure handling are specified.
- [ ] Reconciliation, time zone and cut-off, audit trail, data access and retention are addressed.

## Traceability and authority
- [ ] Source/provider need links forward to requirement IDs; requirement links to design/interface and test case.
- [ ] Open defects, change requests, and downstream consumers are linked and impact assessed.
- [ ] Technical Architect feasibility assessment is recorded.
- [ ] Test Manager testability assessment is recorded.
- [ ] PM scope, risk, and schedule assessment is recorded.
- [ ] BA dispositions every review comment with rationale and revision reference.
- [ ] Customer acceptance and CDO quality exception approval are recorded where applicable.

## Decision record
**Decision:** ☐ Approved ☐ Approved with actions ☐ Rework ☐ Rejected  
**Findings / actions, owner, due date:** __________________________________________  
**BA Team:** __________________ **PM:** __________________ **Architect:** __________________  
**Test Manager:** __________________ **Customer / CDO (as applicable):** __________________
