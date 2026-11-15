# Banking Quality Controls and Rationale

**Document ID:** BNK-QC-009 · **Revision:** 1.0 · **Owner:** Quality Lead  
**Project:** Banking Application (BNK) · **Development team:** Basavanna (Software Engineer), Ranjitha S (Software Engineer)

## 1. Why controls are stricter in a financial system
A defect in a banking application changes money, not just screens. Errors are hard to reverse (posted debits, wrong interest), create regulatory and audit exposure, damage customer trust, and compound silently over time (a rounding error repeats on every instalment). Past defects came from ambiguous requirements, weak reviews, wrongly implemented business rules, and late discovery in testing. Controls are therefore weighted toward the left of the lifecycle: unambiguous rules first, independent review next, and verification evidence before any release.

## 2. Quality objectives and factors
- **Correctness of business rules and calculations** (most important): interest, EMI, fees, limits and rounding match approved rules to the defined precision.
- **Data integrity and consistency:** ledger, balances and history always reconcile; transfers are exactly-once.
- **Security and privacy:** role-based access to customer financial data.
- **Auditability and compliance:** every sensitive action is recorded with actor, time and reason.
- **Reliability and recoverability:** safe behavior on retry, restart, timeout and failure.
- **Timeliness and maintainability:** alerts and reports meet deadlines; rules are table-driven and testable.

## 3. Which requirements need stronger review and approval
| Tier | Requirement type | Review and approval |
|---|---|---|
| Tier 1 | Moves or posts money; calculates interest, fees or EMI; limits and overdraft; security, access and audit; regulatory rules | Checklist review plus a worked-example review by BA, Architect and Test Manager; formula, precision and rounding written in the SRS; Customer sign-off; CDO approves any exception |
| Tier 2 | Transaction history, reconciliation, statements, external interfaces | Standard checklist review; Architect and Test Manager sign |
| Tier 3 | Alerts, reports, UI behavior | Standard review; BA and Test Manager sign |

Requirements marked Critical or High in the register default to Tier 1 or 2. A Tier 1 requirement cannot be baselined without approved test vectors.

## 4. Defect classification
| Class | Examples | Default severity |
|---|---|---|
| Business rules | Wrong interest/EMI, limit not enforced, wrong rounding | Critical |
| Financial accuracy | Balance, history or ledger mismatch, lost reversal | Critical or High |
| Security | Unauthorized access, data exposure | Critical or High |
| Compliance and audit | Missing audit record, wrong retention | High |
| Data integrity | Invalid input accepted, duplicate posting | High |
| Interoperability, Reliability | Interface failure, restart duplicates | Medium or High |
| Efficiency, Maintainability | Slow response, hard-to-test code | Low or Medium |
| Escaped | Any defect found after release or in UAT | Reviewed for CAPA regardless of severity |

Any defect that can produce a wrong amount or posting is at least High; if it can reach a customer balance it is Critical.

## 5. Verification points before release
1. Requirement baseline signed and every Tier 1 rule has approved worked examples.
2. Design and interface contracts reviewed (ledger, payment, alert gateway).
3. Code reviewed by a peer before merge; business rules reviewed against the rule specification.
4. Unit and vector tests for calculations, including boundary and rounding cases.
5. Integration tests: transfer idempotency, concurrency, limits, reversal, reconciliation.
6. Regression on the tagged build; security and access-control tests; audit-trail test.
7. Customer acceptance on agreed scenarios; rollback rehearsed; monitoring verified.
8. Release gate review by PM, Test Manager, Architect and Customer; CEO authorizes business release.

## 6. Handling unresolved high-severity defects
- **Critical:** blocks release. No waiver.
- **High:** must be fixed or carry a written disposition (workaround, customer impact, owner, due date, compensating control) approved by the Customer and CDO. The release gate shows open High defects as a blocker until resolved or dispositioned.
- All unresolved defects are listed in the release notes; any defect that escapes opens a CAPA and its recurrence is checked.

## 7. Meaningful metrics
| Metric | Why it matters here |
|---|---|
| Requirements coverage and Tier 1 coverage | Shows rules without verification evidence |
| Test pass rate (threshold 80%, Tier 1 tests must pass) | Release decision aid; read with the denominator |
| Business-rule and financial-accuracy defects open | Highest-impact defect classes |
| Escaped defect rate | Measures how well reviews and tests catch defects early |
| Defect aging (median open age) | Highlights stalled high-severity defects |
| Reopen rate | Quality of fixes and retests |
| First-pass review yield | Review effectiveness (estimate until review history is captured) |
| Change-impact coverage | Whether changes were analyzed before approval (estimate until captured) |

## 8. Configuration and document control
- Each controlled document has an ID, revision, owner, status and approval date; only the owner edits a baselined item and every change goes through a change request and CCB decision.
- Code, configuration, database migrations and test data live in version control; each release is a tagged build recorded in the release checklist.
- Reviews, tests and defects cite artifact revisions. The RTM links need, requirement, design, code change, test result, and defect/CAPA, and is checked for orphan and stale links at every gate.
- Before release, PM and Test Manager confirm that documents, code tag and test evidence match.

## 9. Corrective and preventive action
Open a CAPA for any Critical defect, escaped defect, repeated defect class or failed control. Contain the customer and data risk first, find the process root cause (for example an unwritten rounding rule), act, and independently verify effectiveness before closure.
