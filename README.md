# Banking Quality OS

A Java + SQLite quality-management workspace for a **Banking Application** (customer accounts, fund transfers, transaction history, loans and interest, limits, alerts, audit, reporting). It demonstrates the QMS flow **PLAN → DEFINE → EXECUTE → VERIFY → MEASURE → REPORT → IMPROVE** through a responsive HTML/CSS/vanilla-JS app and JSON API served by one Java service.

Team: **Basavanna** and **Ranjitha S** (Software Engineers). The displayed profile alternates between them on each server start (demo behavior, not authentication).

## Run
Requires Java 17+ and Maven 3.8+.
```
mvn test
mvn package
java -jar target/banking-quality-os-1.0.0.jar
```
Open http://localhost:8080. Env vars: `PORT`, `QMS_DB` (default `data/qms.db`). The database is created and seeded on first launch.

## What it covers
- Requirements with Tier 1/2/3 review rigor, bidirectional traceability, RTM CSV export
- Reviews, test cases and defects (banking classes: Business rules, Financial accuracy, Security, Compliance & audit, Data integrity, Escaped…)
- Evidence-based release gate: no critical defects, no unresolved high defects, traceability, pass rate ≥ 80%, approval, rollback, monitoring
- CAPA that cannot close without independent effectiveness evidence
- 9 controlled documents downloadable as Word/Excel, including **Banking Quality Controls and Rationale** (answers the problem-statement questions and justifies controls)

First-pass review yield and change-impact coverage are marked demo estimates. There is no authentication or TLS and SQLite on free hosts is not durable; see the deployment notes in the Dockerfile/render.yaml and do not store real customer data.

## API
`GET /api/health | dashboard | report | release`, `PATCH /api/release`, and `GET/POST /api/{requirements,defects,reviews,tests,capas}`, `PATCH/DELETE /api/{resource}/{id}`, `GET /docs/{document}.md|.docx|.xlsx`.
