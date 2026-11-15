# Requirements Traceability Matrix (RTM)

**Project:** Banking Application (BNK) · **Baseline / revision:** ______ · **As of:** ______ · **Maintainer:** BA Team

Maintain traceability in both directions. Use stable IDs and versioned links; a link alone is not evidence that the linked artifact is current or passed.

| Provider need / source | Requirement ID / version | Acceptance criteria / risk | Design / API / data map | Implementation / change | Test ID / method / result | Defect / CAPA | Owner / approval |
|---|---|---|---|---|---|---|---|
| | | | | | | | |
| | | | | | | | |
| | | | | | | | |

## Review queries
- **Forward gaps:** provider needs without SRS requirements; requirements without design/implementation/test links.
- **Reverse gaps:** design/code/tests without an approved requirement, justified technical constraint, or approved change.
- **Unverified:** approved requirements with no current passing evidence or accepted exception.
- **Change impact:** changed requirement with no identified design, code, test, regression, provider, and release impact.
- **Nonconformance links:** each relevant defect/CAPA points to affected requirement(s) and retest evidence.

## Example interface coverage
| Need | Requirement | Design / contract | Test evidence | Defect / disposition |
|---|---|---|---|---|
| Avoid duplicate fund transfer on retry | REQ-002 | API-04 idempotency contract | TC-204 transfer idempotency | Link any double-posting issue |
| Loan EMI matches approved rule | REQ-003 | Design §5 interest rule spec | TC-307 EMI vectors | DF-018 open; release blocker |
| History reconciles to balance | REQ-005 | API-12 ledger contract | TC-512 reconciliation incl. reversals | DF-021 in progress |

**Reviewed by BA Team:** ______ **Technical Architect:** ______ **Test Manager:** ______ **PM:** ______  
**Orphan links dispositioned / date:** ______
