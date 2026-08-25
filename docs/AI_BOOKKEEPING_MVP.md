# AI Bookkeeping MVP

This fork uses ezBookkeeping v1.6.1 as its accounting, import, authentication,
scheduled-transaction, and reporting foundation.

## Fixed product decisions

- Single user, Simplified Chinese, CNY, Asia/Shanghai.
- Docker Compose deployment on an x86_64 NAS with 8 GB RAM, LAN access only.
- Desktop browser is the primary client.
- Accounts include WeChat balance, Alipay balance, Huabei, JD Baitiao,
  debit cards, credit cards, and cash.
- Transactions may be entered manually, described in natural language, imported
  from CSV/Excel, or extracted from screenshots.
- High-confidence imported records are posted automatically. Ambiguous,
  incomplete, duplicate, and suspected transfer records go to an exception queue.
- Cancelled natural-language and screenshot entries are persisted as text-only
  review items that can be retried or dismissed; source screenshots are not kept.
- Transactions automatically created during the current browser session can be
  undone together from the home-page AI entry card.
- One total monthly budget resets every month without rollover. In-app warnings
  are shown at 80% and 100%.
- Recurring income and expenses support monthly salary, monthly mortgage, and
  yearly memberships. Missed occurrences are created after service recovery.
- On-demand AI reports compare the current month with the previous month and are
  retained as report history.
- Successful screenshot processing deletes the original file. Import metadata,
  hashes, and parsed records are retained for audit and deduplication.

## Product asset rules

- AI may create a product asset automatically for durable products with resale
  value and apply category defaults that remain user-editable.
- Book value uses daily straight-line depreciation from purchase amount to the
  configured residual amount over the configured useful life.
- Before sale, average daily cost is purchase amount divided by inclusive held
  days.
- After sale, average daily cost is purchase amount minus sale amount, divided by
  inclusive held days.
- A sale creates an income transaction, stops the holding period, and preserves
  the link between purchase, asset, and sale transaction.

## AI and OCR boundary

- A local OCR worker extracts text from screenshots.
- Extracted text may be sent to DeepSeek through an OpenAI-compatible API.
- Monthly reports send only aggregate income, expense, category totals, and
  transaction counts to DeepSeek; transaction comments and source files are excluded.
- The original image is deleted only after parsing and persistence succeed.
- Merchant/category corrections are stored locally and take priority over AI.

## Explicit MVP exclusions

- A-share and fund holdings are deferred, but the architecture must leave room
  for an investment module.
- No foreign currency.
- No installment-plan accounting.
- No mobile PWA-specific work.
- No email or external budget notifications.
