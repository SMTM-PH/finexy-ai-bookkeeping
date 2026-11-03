# Ledger report synthetic fixture

`v1.json` is the shared source for SQLite/PostgreSQL/MySQL service tests, the real HTTP verifier, and Web contract/pagination tests. The Web adapter is `src/lib/__tests__/fixtures/ledger_report.ts`; Android acceptance can consume the same file when implemented. All names, records and amounts are synthetic. IDs and integer minor-unit amounts are decimal **strings**; `transactionTime` is Unix **seconds**, while the direct database adapter derives its millisecond-like transaction sequence as `seconds * 1000 + fixtureTransactionId`. Dates use the declared IANA timezone.

The `expected` section is an independently specified oracle. Consumers must not calculate expected totals by calling production report code. `cases` uses the fixture's stable resource IDs; the HTTP adapter maps those strings to IDs allocated by the disposable server and maps response IDs back before comparing.

The direct adapter seeds physical transfer legs, including asymmetric tag associations, to prove union/deduplication. The public transaction API accepts one logical transfer and applies its union of tags to both legs. These adapters produce the same logical report, IDs after mapping, totals and filter matches.

An account's `balance` means the opening balance supplied at creation. The direct adapter applies each subsequent physical transaction's balance effect once; the HTTP server does so through its normal writes. Generated opening adjustments are not added a second time.

Public account creation generates its initial balance adjustment, with `balanceTarget == adjustment`. Adding another adjustment is rejected when any transaction exists. Therefore the portable base uses target/delta `1000`; `variants.historicalAdjustment` separately specifies target `1000` and delta `-400` for an existing historical database row. The SQLite test applies that explicit variant and verifies both fields and totals. It is **not** claimed as an HTTP-created state. No test mutates real user data to create it.

`databaseOnly` metadata covers malformed/foreign references rejected by the direct service tests. It is never posted as owner metadata by the HTTP adapter. Extra malformed/overflow/boundary tests remain additional tests; the shared base does not replace them.
