# MCP setup

The NAS deployment exposes a Streamable HTTP MCP server at:

```text
http://<NAS-IP>:<PORT>/mcp
```

Generate a dedicated MCP token in Finexy under **Settings → Security → Generate Token → MCP Token**. Use a short expiration where practical and revoke it when a client is retired.

## Codex

Set the token outside the repository:

```powershell
[Environment]::SetEnvironmentVariable('EZBOOKKEEPING_MCP_TOKEN', '<token>', 'User')
```

Add this to `~/.codex/config.toml`, replacing the URL with the LAN address when Codex does not run on the NAS host:

```toml
[mcp_servers.ezbookkeeping]
url = "http://localhost:8080/mcp"
bearer_token_env_var = "EZBOOKKEEPING_MCP_TOKEN"
default_tools_approval_mode = "writes"
tool_timeout_sec = 60
```

Restart Codex, then use `/mcp` or the MCP settings page to verify the connection.

## Other MCP clients

Use a Streamable HTTP connection and send:

```text
Authorization: Bearer <token>
```

Generic configuration shape:

```json
{
  "mcpServers": {
    "ezbookkeeping": {
      "type": "streamable-http",
      "url": "http://localhost:8080/mcp",
      "headers": {
        "Authorization": "Bearer <token>"
      }
    }
  }
}
```

## Troubleshooting

- `404`: MCP is disabled or the URL is missing `/mcp`.
- `401`: the token is missing, expired, revoked, or is not an MCP token.
- Connection timeout: use the NAS LAN IP instead of `localhost` when the agent runs on another machine.
- Tools connect but writes fail: query accounts and secondary categories first; tool inputs match names exactly.
