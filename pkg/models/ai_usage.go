package models

// AIUsageRecord stores metering only: never prompts, responses, keys or bill data.
type AIUsageRecord struct {
	Id                  string `xorm:"PK VARCHAR(32)" json:"-"`
	Uid                 int64  `xorm:"NOT NULL INDEX(uid_time)" json:"-"`
	CreatedUnixTime     int64  `xorm:"NOT NULL INDEX(uid_time)" json:"time"`
	Provider            string `xorm:"VARCHAR(40) NOT NULL" json:"provider"`
	ModelId             string `xorm:"VARCHAR(100) NOT NULL" json:"modelId"`
	Success             bool   `xorm:"NOT NULL" json:"success"`
	UsageKnown          bool   `xorm:"NOT NULL" json:"usageKnown"`
	CacheUsageKnown     bool   `xorm:"NOT NULL" json:"cacheUsageKnown"`
	ReasoningUsageKnown bool   `xorm:"NOT NULL" json:"reasoningUsageKnown"`
	InputTokens         int64  `xorm:"NOT NULL" json:"inputTokens"`
	OutputTokens        int64  `xorm:"NOT NULL" json:"outputTokens"`
	TotalTokens         int64  `xorm:"NOT NULL" json:"totalTokens"`
	CachedTokens        int64  `xorm:"NOT NULL" json:"cachedTokens"`
	ReasoningTokens     int64  `xorm:"NOT NULL" json:"reasoningTokens"`
	DurationMs          int64  `xorm:"NOT NULL" json:"durationMs"`
}

type AIUsageSummary struct {
	CacheMeteredRequests     int64 `json:"cacheMeteredRequests"`
	ReasoningMeteredRequests int64 `json:"reasoningMeteredRequests"`
	Requests                 int64 `json:"requests"`
	FailedRequests           int64 `json:"failedRequests"`
	UnmeteredRequests        int64 `json:"unmeteredRequests"`
	InputTokens              int64 `json:"inputTokens"`
	OutputTokens             int64 `json:"outputTokens"`
	TotalTokens              int64 `json:"totalTokens"`
	CachedTokens             int64 `json:"cachedTokens"`
	ReasoningTokens          int64 `json:"reasoningTokens"`
	DurationMs               int64 `json:"durationMs"`
	FirstRequestTime         int64 `json:"firstRequestTime"`
}

type AIUsageResponse struct {
	Today     *AIUsageSummary  `json:"today"`
	Month     *AIUsageSummary  `json:"month"`
	All       *AIUsageSummary  `json:"all"`
	Recent    []*AIUsageRecord `json:"recent"`
	UpdatedAt int64            `json:"updatedAt"`
	Timezone  string           `json:"timezone"`
}

type AIBalanceInfo struct {
	Currency        string `json:"currency"`
	TotalBalance    string `json:"totalBalance"`
	GrantedBalance  string `json:"grantedBalance"`
	ToppedUpBalance string `json:"toppedUpBalance"`
}

type AIBalanceResponse struct {
	Status    string           `json:"status"`
	Available bool             `json:"available"`
	Balances  []*AIBalanceInfo `json:"balances"`
	UpdatedAt int64            `json:"updatedAt"`
}
