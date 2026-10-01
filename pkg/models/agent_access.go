package models

// AgentAccessSetting is a user-owned preference, separate from administrator restrictions.
// No row means MCP access is enabled, preserving existing installations.
type AgentAccessSetting struct {
	Uid         int64 `xorm:"PK"`
	MCPDisabled bool  `xorm:"'mcp_disabled' NOT NULL"`
}

type AgentAccessUpdateRequest struct {
	MCPEnabled *bool `json:"mcpEnabled" binding:"required"`
}

type AgentAccessResponse struct {
	MCPEnabled   bool `json:"mcpEnabled"`
	MCPAvailable bool `json:"mcpAvailable"`
}
