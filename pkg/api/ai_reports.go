package api

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"reflect"
	"sort"
	"strings"
	"time"

	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/mayswind/ezbookkeeping/pkg/errs"
	"github.com/mayswind/ezbookkeeping/pkg/llm"
	"github.com/mayswind/ezbookkeeping/pkg/llm/data"
	"github.com/mayswind/ezbookkeeping/pkg/log"
	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/mayswind/ezbookkeeping/pkg/services"
	"github.com/mayswind/ezbookkeeping/pkg/settings"
)

type AIReportsApi struct {
	ApiUsingConfig
	reports      *services.AIReportService
	transactions *services.TransactionService
	categories   *services.TransactionCategoryService
}

var AIReports = &AIReportsApi{
	ApiUsingConfig: ApiUsingConfig{container: settings.Container}, reports: services.AIReports,
	transactions: services.Transactions, categories: services.TransactionCategories,
}

type reportCategoryAmount struct {
	Name   string `json:"name"`
	Amount int64  `json:"amount"`
}

type reportMonthSummary struct {
	YearMonth    int32                  `json:"yearMonth"`
	Income       int64                  `json:"income"`
	Expense      int64                  `json:"expense"`
	IncomeCount  int                    `json:"incomeCount"`
	ExpenseCount int                    `json:"expenseCount"`
	TopExpenses  []reportCategoryAmount `json:"topExpenses"`
}

func (a *AIReportsApi) ListHandler(c *core.WebContext) (any, *errs.Error) {
	reports, err := a.reports.List(c, c.GetCurrentUid())
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	result := make([]*models.AIReportInfoResponse, 0, len(reports))
	for _, report := range reports {
		result = append(result, report.ToInfoResponse())
	}
	return result, nil
}

func (a *AIReportsApi) GenerateHandler(c *core.WebContext) (any, *errs.Error) {
	config := a.CurrentConfig()
	if config.TextRecognitionLLMConfig == nil || config.TextRecognitionLLMConfig.LLMProvider == "" || !config.TransactionFromAITextRecognition {
		return nil, errs.ErrLargeLanguageModelProviderNotEnabled
	}
	var request models.AIReportGenerateRequest
	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}
	comparedYearMonth, ok := previousYearMonth(request.YearMonth)
	if !ok {
		return nil, errs.ErrAIReportYearMonthInvalid
	}

	uid := c.GetCurrentUid()
	current, err := a.buildMonthSummary(c, uid, request.YearMonth)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	previous, err := a.buildMonthSummary(c, uid, comparedYearMonth)
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	payload, err := json.Marshal(map[string]any{"currentMonth": current, "previousMonth": previous, "currency": "CNY", "amountUnit": "cent"})
	if err != nil {
		return nil, errs.ErrOperationFailed
	}
	hash := sha256.Sum256(payload)
	provider := config.TextRecognitionLLMConfig.LLMProvider
	modelID := getLLMModelID(config.TextRecognitionLLMConfig)
	report, err := a.reports.CreatePending(c, uid, request.YearMonth, comparedYearMonth, provider, modelID, hex.EncodeToString(hash[:]))
	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}

	systemPrompt := "你是个人财务分析助手。根据用户提供的人民币收支汇总，生成简体中文月度分析。必须客观、简洁，不提供投资收益承诺。内容包含：本月概览、与上月对比、支出结构、异常变化、三条可执行建议。金额由分换算为元。当前月可能尚未结束，必须明确提示月中数据与完整上月直接比较的局限。只返回 JSON：{\"content\":\"纯文本报告\"}。"
	llmRequest := &data.LargeLanguageModelRequest{
		Stream: false, SystemPrompt: systemPrompt, UserPrompt: payload,
		UserPromptType:         data.LARGE_LANGUAGE_MODEL_REQUEST_PROMPT_TYPE_TEXT,
		ResponseJsonObjectType: reflect.TypeOf(models.AIReportLLMResult{}),
	}
	llmResponse, llmErr := llm.Container.GetJsonResponseByTextRecognitionModel(c, uid, config, llmRequest)
	if llmErr != nil {
		_ = a.reports.Fail(c, uid, report.AiReportId, "AI 分析生成失败")
		report.Status, report.ErrorMessage = models.AI_REPORT_STATUS_FAILED, "AI 分析生成失败"
		log.Errorf(c, "[ai_reports.GenerateHandler] failed to generate report for user \"uid:%d\", because %s", uid, llmErr.Error())
		return report.ToInfoResponse(), nil
	}
	var result models.AIReportLLMResult
	if llmResponse == nil || json.Unmarshal([]byte(llmResponse.Content), &result) != nil || strings.TrimSpace(result.Content) == "" {
		_ = a.reports.Fail(c, uid, report.AiReportId, "AI 返回内容无效")
		report.Status, report.ErrorMessage = models.AI_REPORT_STATUS_FAILED, "AI 返回内容无效"
		return report.ToInfoResponse(), nil
	}
	report.Content = strings.TrimSpace(result.Content)
	report.Status = models.AI_REPORT_STATUS_COMPLETED
	if err := a.reports.Complete(c, uid, report.AiReportId, report.Content); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}
	return report.ToInfoResponse(), nil
}

func (a *AIReportsApi) buildMonthSummary(c core.Context, uid int64, yearMonth int32) (*reportMonthSummary, error) {
	year, month := yearMonth/100, yearMonth%100
	transactions, err := a.transactions.GetTransactionsInMonthByPage(c, uid, year, month, 0, nil, nil, nil, false, "", "", core.MATCH_MODE_DEFAULT, false)
	if err != nil {
		return nil, err
	}
	categories, err := a.categories.GetAllCategoriesByUid(c, uid, 0, -1)
	if err != nil {
		return nil, err
	}
	categoryNames := make(map[int64]string, len(categories))
	for _, category := range categories {
		categoryNames[category.CategoryId] = category.Name
	}

	summary := &reportMonthSummary{YearMonth: yearMonth, TopExpenses: make([]reportCategoryAmount, 0)}
	expenses := make(map[int64]int64)
	for _, transaction := range transactions {
		switch transaction.Type {
		case models.TRANSACTION_DB_TYPE_INCOME:
			summary.Income += transaction.Amount
			summary.IncomeCount++
		case models.TRANSACTION_DB_TYPE_EXPENSE:
			summary.Expense += transaction.Amount
			summary.ExpenseCount++
			expenses[transaction.CategoryId] += transaction.Amount
		}
	}
	for categoryID, amount := range expenses {
		name := categoryNames[categoryID]
		if name == "" {
			name = "未分类"
		}
		summary.TopExpenses = append(summary.TopExpenses, reportCategoryAmount{Name: name, Amount: amount})
	}
	sort.Slice(summary.TopExpenses, func(i, j int) bool { return summary.TopExpenses[i].Amount > summary.TopExpenses[j].Amount })
	if len(summary.TopExpenses) > 10 {
		summary.TopExpenses = summary.TopExpenses[:10]
	}
	return summary, nil
}

func previousYearMonth(value int32) (int32, bool) {
	year, month := value/100, value%100
	if year < 2000 || month < 1 || month > 12 {
		return 0, false
	}
	previous := time.Date(int(year), time.Month(month), 1, 0, 0, 0, 0, time.UTC).AddDate(0, -1, 0)
	return int32(previous.Year()*100 + int(previous.Month())), true
}

func getLLMModelID(config *settings.LLMConfig) string {
	switch config.LLMProvider {
	case settings.OpenAILLMProvider:
		return config.OpenAIModelID
	case settings.OpenAICompatibleLLMProvider:
		return config.OpenAICompatibleModelID
	case settings.AnthropicLLMProvider:
		return config.AnthropicModelID
	case settings.AnthropicCompatibleLLMProvider:
		return config.AnthropicCompatibleModelID
	case settings.OpenRouterLLMProvider:
		return config.OpenRouterModelID
	case settings.OllamaLLMProvider:
		return config.OllamaModelID
	case settings.LMStudioLLMProvider:
		return config.LMStudioModelID
	case settings.GoogleAILLMProvider:
		return config.GoogleAIModelID
	default:
		return fmt.Sprintf("%s-model", config.LLMProvider)
	}
}
