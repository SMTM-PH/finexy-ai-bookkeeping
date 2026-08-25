---
name: ezbookkeeping
description: Manage the self-hosted ezBookkeeping personal finance app through its MCP server or bundled API CLI. Use when Codex or another agent needs to record income, expenses, or transfers; inspect balances, accounts, categories, tags, or transactions; reconcile a natural-language bookkeeping request; or analyze personal spending. Prefer MCP tools when available and use the scripts only as a fallback.
---

# ezBookkeeping

Use the authenticated MCP server first. Its tools are the safest and most portable path across Codex, ChatGPT desktop, IDE agents, and other MCP clients.

## Workflow

1. Discover tools from the configured `ezbookkeeping` MCP server. Tool names may be namespaced; match these suffixes:
   - `query_all_accounts` / `query_all_accounts_balance`
   - `query_all_transaction_categories`
   - `query_all_transaction_tags`
   - `query_transactions`
   - `add_transaction`
2. Before adding a transaction, query accounts and categories. Never invent an account or secondary category name.
3. Convert amounts to positive decimal strings such as `16.00`. Express time in RFC 3339 with the user's timezone, for example `2026-08-11T12:30:00+08:00`.
4. Use `dry_run: true` first when account/category matching or intent is uncertain. Only perform the write when the request clearly authorizes it or after the user confirms the preview.
5. For analysis, query the smallest relevant time range and summarize the returned data. Do not mutate records during analysis.
6. Never send bookkeeping text to DeepSeek or another model through the app merely to interpret a request. Parse the user's instruction directly and call the deterministic MCP tools.

## Safety

- Treat balances, transactions, account names, and tokens as private financial data.
- Never print or persist the MCP token in chat, logs, reports, or repository files.
- Treat `add_transaction` as a write. Use its `dry_run` mode for previews.
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
