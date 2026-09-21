package models

// AIConfigurationUpdateRequest represents the owner-managed server AI settings.
// An empty API key keeps the currently persisted or environment-provided key.
type AIConfigurationUpdateRequest struct {
	Enabled        bool   `json:"enabled"`
	ImageEnabled   bool   `json:"imageEnabled"`
	BaseURL        string `json:"baseUrl" binding:"required,notBlank,max=500"`
	APIKey         string `json:"apiKey" binding:"omitempty,max=500"`
	ModelID        string `json:"modelId" binding:"required,notBlank,max=100"`
	EnableThinking string `json:"enableThinking" binding:"omitempty,max=10"`
	RequestTimeout uint32 `json:"requestTimeout" binding:"omitempty,min=1000,max=300000"`
}

// AIConfigurationResponse is safe to send to the browser and never includes a key.
type AIConfigurationResponse struct {
	Enabled          bool   `json:"enabled"`
	ImageEnabled     bool   `json:"imageEnabled"`
	Provider         string `json:"provider"`
	BaseURL          string `json:"baseUrl"`
	ModelID          string `json:"modelId"`
	EnableThinking   string `json:"enableThinking"`
	RequestTimeout   uint32 `json:"requestTimeout"`
	APIKeyConfigured bool   `json:"apiKeyConfigured"`
	Editable         bool   `json:"editable"`
}
