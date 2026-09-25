---
name: ezbookkeeping
description: Manage the self-hosted Finexy personal finance app through its MCP server or bundled API CLI. Use when Codex or another agent needs to recognize a receipt image, record income, expenses, or transfers, inspect balances or transactions, reconcile bookkeeping requests, or analyze personal spending. Prefer MCP tools when available and use the scripts only as a fallback.
---

# Finexy

Use the authenticated MCP server first. Its tools are the safest and most portable path across Codex, ChatGPT desktop, IDE agents, and other MCP clients.

## Workflow

1. Discover tools from the configured `ezbookkeeping` MCP server. Tool names may be namespaced; match these suffixes:
   - `query_all_accounts` / `query_all_accounts_balance`
   - `query_all_transaction_categories`
   - `query_all_transaction_tags`
   - `query_transactions`
   - `add_transaction`
   - `recognize_receipt_image`
2. Before adding a transaction, query accounts and categories. Never invent an account or secondary category name.
3. Convert amounts to positive decimal strings such as `16.00`. Express time in RFC 3339 with the user's timezone, for example `2026-08-11T12:30:00+08:00`.
4. Use `dry_run: true` first when account/category matching or intent is uncertain. Only perform the write when the request clearly authorizes it or after the user confirms the preview.
5. For analysis, query the smallest relevant time range and summarize the returned data. Do not mutate records during analysis.
6. For an attached receipt, call `recognize_receipt_image` with raw base64 image data, the matching MIME type, and the user's UTC offset in minutes. The tool calls Finexy's configured vision model directly, returns a draft, and never saves a transaction.
7. Review the recognized draft and its `*_matched` flags. Resolve unmatched account/category names before calling `add_transaction`; use `dry_run: true` first unless the user's requested values are already unambiguous.
8. Never send ordinary bookkeeping text to DeepSeek or another model through the app merely to interpret a request. Parse the user's instruction directly and call the deterministic MCP tools.

## Safety

- Treat balances, transactions, account names, and tokens as private financial data.
- Never print or persist the MCP token in chat, logs, reports, or repository files.
- Treat `add_transaction` as a write. Use its `dry_run` mode for previews.
- Treat receipt images as private financial data. Send them only when the user asks for image recognition; do not persist base64 data or echo it in chat or logs.
- Recognition is advisory. Never turn a recognized draft into a saved transaction without checking amount, type, date, account, and category.
- If multiple accounts or categories plausibly match, ask one focused question instead of guessing.
- For transfers, provide both source and destination accounts and amounts.

## CLI fallback

Use the bundled CLI only when MCP tools are unavailable. Read [references/mcp-setup.md](references/mcp-setup.md) when configuring a client or troubleshooting authentication.

List commands:

```bash
sh scripts/ebktools.sh list
```

```powershell
scripts\ebktools.ps1 list
```

Call a command:

```bash
sh scripts/ebktools.sh [global-options] <command> [command-options]
```

```powershell
scripts\ebktools.ps1 [global-options] <command> [command-options]
```

The fallback requires `EBKTOOL_SERVER_BASEURL` and `EBKTOOL_TOKEN` in the environment or the user's home `.env` file. Run `help <command>` before an unfamiliar write command.
