package models

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"reflect"
	"strconv"
	"testing"

	"github.com/gin-gonic/gin/binding"
	"github.com/go-playground/validator/v10"
)

func TestTransactionTemplateQueryLedgerContract(t *testing.T) {
	requests := []struct {
		name string
		new  func() any
	}{
		{"list", func() any { return &TransactionTemplateListRequest{} }},
		{"get", func() any { return &TransactionTemplateGetRequest{} }},
	}
	for _, request := range requests {
		t.Run(request.name, func(t *testing.T) {
			cases := []struct {
				name    string
				ledger  *string
				want    int64
				wantErr bool
			}{
				{name: "omitted personal ledger"},
				{name: "explicit personal ledger", ledger: templateLedgerString("0")},
				{name: "shared ledger beyond JS precision", ledger: templateLedgerString("9007199254740993"), want: 9007199254740993},
				{name: "maximum int64 ledger", ledger: templateLedgerString("9223372036854775807"), want: 9223372036854775807},
				{name: "negative ledger", ledger: templateLedgerString("-1"), wantErr: true},
				{name: "fractional ledger", ledger: templateLedgerString("1.5"), wantErr: true},
				{name: "overflow ledger", ledger: templateLedgerString("9223372036854775808"), wantErr: true},
			}
			for _, test := range cases {
				t.Run(test.name, func(t *testing.T) {
					query := url.Values{"id": {"1"}, "templateType": {"2"}}
					if test.ledger != nil {
						query.Set("ledgerId", *test.ledger)
					}
					dto := request.new()
					err := binding.Query.Bind(httptest.NewRequest(http.MethodGet, "/?"+query.Encode(), nil), dto)
					if test.wantErr {
						if err == nil {
							t.Fatal("invalid ledger query was accepted")
						}
						return
					}
					if err != nil {
						t.Fatal(err)
					}
					if got := templateRequestLedger(t, dto); got != test.want {
						t.Fatalf("ledgerId = %d, want %d", got, test.want)
					}
				})
			}
		})
	}
}

func TestTransactionTemplateCommandLedgerContract(t *testing.T) {
	requests := []struct {
		name string
		new  func() any
	}{
		{"create", func() any { return &TransactionTemplateCreateRequest{} }},
		{"modify", func() any { return &TransactionTemplateModifyRequest{} }},
		{"modify name", func() any { return &TransactionTemplateModifyNameRequest{} }},
		{"hide", func() any { return &TransactionTemplateHideRequest{} }},
		{"move", func() any { return &TransactionTemplateMoveRequest{} }},
		{"delete", func() any { return &TransactionTemplateDeleteRequest{} }},
	}
	for _, request := range requests {
		t.Run(request.name, func(t *testing.T) {
			cases := []struct {
				name    string
				body    string
				want    int64
				wantErr bool
			}{
				{name: "omitted personal ledger", body: `{}`},
				{name: "explicit personal ledger", body: `{"ledgerId":"0"}`},
				{name: "shared ledger beyond JS precision", body: `{"ledgerId":"9007199254740993"}`, want: 9007199254740993},
				{name: "maximum int64 ledger", body: `{"ledgerId":"9223372036854775807"}`, want: 9223372036854775807},
				{name: "negative ledger", body: `{"ledgerId":"-1"}`, wantErr: true},
				{name: "numeric ledger", body: `{"ledgerId":9007199254740993}`, wantErr: true},
				{name: "fractional ledger", body: `{"ledgerId":"1.5"}`, wantErr: true},
				{name: "overflow ledger", body: `{"ledgerId":"9223372036854775808"}`, wantErr: true},
			}
			for _, test := range cases {
				t.Run(test.name, func(t *testing.T) {
					dto := request.new()
					err := json.Unmarshal([]byte(test.body), dto)
					if err == nil {
						field, exists := reflect.TypeOf(dto).Elem().FieldByName("LedgerId")
						if !exists || field.Tag.Get("binding") == "" {
							t.Fatal("command must validate its ledgerId")
						}
						// Validate only this field: the fixtures deliberately omit unrelated
						// required command fields and do not register API custom validators.
						err = validator.New().Var(templateRequestLedger(t, dto), field.Tag.Get("binding"))
					}
					if test.wantErr {
						if err == nil {
							t.Fatal("invalid ledger command was accepted")
						}
						return
					}
					if err != nil {
						t.Fatal(err)
					}
					if got := templateRequestLedger(t, dto); got != test.want {
						t.Fatalf("ledgerId = %d, want %d", got, test.want)
					}
					encoded, err := json.Marshal(dto)
					if err != nil {
						t.Fatal(err)
					}
					var fields map[string]json.RawMessage
					if err := json.Unmarshal(encoded, &fields); err != nil {
						t.Fatal(err)
					}
					if got, want := string(fields["ledgerId"]), strconv.Quote(strconv.FormatInt(test.want, 10)); got != want {
						t.Fatalf("encoded ledgerId = %s, want %s", got, want)
					}
				})
			}
		})
	}
}

func TestTransactionTemplateResponsePreservesLedgerAndTransferIDs(t *testing.T) {
	for _, ledgerID := range []int64{0, 9007199254740993} {
		for _, templateType := range []TransactionTemplateType{TRANSACTION_TEMPLATE_TYPE_NORMAL, TRANSACTION_TEMPLATE_TYPE_SCHEDULE} {
			t.Run(strconv.FormatInt(ledgerID, 10)+"/"+strconv.Itoa(int(templateType)), func(t *testing.T) {
				template := &TransactionTemplate{
					TemplateId: 9007199254740995, LedgerId: ledgerID, TemplateType: templateType,
					Name: "Synthetic transfer", Type: TRANSACTION_TYPE_TRANSFER, CategoryId: 9007199254740997,
					AccountId: 9007199254740999, RelatedAccountId: 9007199254741001,
					Amount: 12345, RelatedAccountAmount: 67890, ScheduledTimezoneUtcOffset: -240,
					TagIds: "9007199254741003,9223372036854775807", HideAmount: true, Hidden: true,
					Comment: "Synthetic fixture", CreatedUnixTime: 1700000000,
				}
				response := template.ToTransactionTemplateInfoResponse(480)
				if response.LedgerId != ledgerID || response.SourceAmount != 12345 || response.DestinationAmount != 67890 ||
					!response.HideAmount || !response.Hidden {
					t.Fatal("template response lost its ledger or transfer fields")
				}
				wantOffset := int16(480)
				if templateType == TRANSACTION_TEMPLATE_TYPE_SCHEDULE {
					wantOffset = -240
				}
				if response.UtcOffset != wantOffset {
					t.Fatalf("utcOffset = %d, want %d", response.UtcOffset, wantOffset)
				}
				encoded, err := json.Marshal(response)
				if err != nil {
					t.Fatal(err)
				}
				var fields map[string]json.RawMessage
				if err := json.Unmarshal(encoded, &fields); err != nil {
					t.Fatal(err)
				}
				for field, want := range map[string]int64{
					"id": template.TemplateId, "ledgerId": ledgerID, "categoryId": template.CategoryId,
					"sourceAccountId": template.AccountId, "destinationAccountId": template.RelatedAccountId,
				} {
					if got := string(fields[field]); got != strconv.Quote(strconv.FormatInt(want, 10)) {
						t.Fatalf("%s = %s, must preserve the ID as a string", field, got)
					}
				}
				var restored TransactionTemplateInfoResponse
				if err := json.Unmarshal(encoded, &restored); err != nil {
					t.Fatal(err)
				}
				if !reflect.DeepEqual(response, &restored) {
					t.Fatal("template response did not round-trip exactly")
				}
			})
		}
	}
}

func TestTransactionScheduleFrequencyValidRules(t *testing.T) {
	cases := []struct {
		name string
		kind TransactionScheduleFrequencyType
		raw  string
	}{
		{"disabled", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DISABLED, ""},
		{"daily web placeholder", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DAILY, "0"},
		{"daily legacy placeholder", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DAILY, "1"},
		{"weekly Sunday", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_WEEKLY, "0"},
		{"weekly Saturday", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_WEEKLY, "6"},
		{"weekly unordered selection", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_WEEKLY, "6,2,0"},
		{"weekly duplicate before normalization", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_WEEKLY, "1,1"},
		{"monthly first day", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_MONTHLY, "1"},
		{"monthly last positive day", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_MONTHLY, "31"},
		{"monthly last day", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_MONTHLY, "-1"},
		{"monthly earliest relative day", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_MONTHLY, "-31"},
		{"monthly mixed selections", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_MONTHLY, "31,-1,1,-31"},
		{"yearly first date", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_YEARLY, "101"},
		{"yearly last date", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_YEARLY, "1231"},
		{"yearly leap day", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_YEARLY, "229"},
		{"yearly multiple dates", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_YEARLY, "229,101,430"},
		{"every day interval", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_EVERY_N_DAYS, "1"},
		{"every thirty days", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_EVERY_N_DAYS, "30"},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			if err := ValidateTransactionScheduleFrequency(test.kind, test.raw); err != nil {
				t.Fatalf("valid frequency rejected: %v", err)
			}
		})
	}
}

func TestTransactionScheduleFrequencyRejectsInvalidRules(t *testing.T) {
	cases := []struct {
		name string
		kind TransactionScheduleFrequencyType
		raw  string
	}{
		{"disabled nonempty", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DISABLED, "0"},
		{"daily wrong placeholder", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DAILY, "2"},
		{"daily negative placeholder", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DAILY, "-1"},
		{"daily multiple placeholders", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DAILY, "0,1"},
		{"weekly below range", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_WEEKLY, "-1"},
		{"weekly above range", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_WEEKLY, "7"},
		{"weekly partially invalid", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_WEEKLY, "1,7"},
		{"monthly zero", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_MONTHLY, "0"},
		{"monthly above range", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_MONTHLY, "32"},
		{"monthly relative below range", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_MONTHLY, "-32"},
		{"monthly partially invalid", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_MONTHLY, "1,-32"},
		{"yearly missing month", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_YEARLY, "1"},
		{"yearly missing day", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_YEARLY, "100"},
		{"yearly month overflow", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_YEARLY, "1301"},
		{"yearly February overflow", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_YEARLY, "230"},
		{"yearly April overflow", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_YEARLY, "431"},
		{"yearly December overflow", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_YEARLY, "1232"},
		{"yearly negative date", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_YEARLY, "-101"},
		{"yearly partially invalid", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_YEARLY, "229,230"},
		{"day interval zero", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_EVERY_N_DAYS, "0"},
		{"day interval negative", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_EVERY_N_DAYS, "-1"},
		{"multiple day intervals", TRANSACTION_SCHEDULE_FREQUENCY_TYPE_EVERY_N_DAYS, "1,2"},
		{"unknown type", TransactionScheduleFrequencyType(6), "1"},
		{"unknown maximum type", TransactionScheduleFrequencyType(255), "1"},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			if err := ValidateTransactionScheduleFrequency(test.kind, test.raw); err == nil {
				t.Fatal("invalid frequency was accepted")
			}
		})
	}
}

func TestTransactionScheduleFrequencyRejectsCorruptValuesBeforeNormalization(t *testing.T) {
	for _, kind := range []TransactionScheduleFrequencyType{
		TRANSACTION_SCHEDULE_FREQUENCY_TYPE_DAILY, TRANSACTION_SCHEDULE_FREQUENCY_TYPE_WEEKLY,
		TRANSACTION_SCHEDULE_FREQUENCY_TYPE_MONTHLY, TRANSACTION_SCHEDULE_FREQUENCY_TYPE_YEARLY,
		TRANSACTION_SCHEDULE_FREQUENCY_TYPE_EVERY_N_DAYS,
	} {
		for _, raw := range []string{"", "01", "0101", "-0", "+1", " 1", "1 ", "1.0", "1e2", "1,", ",1", "1,,2", "1,invalid", "9223372036854775808"} {
			t.Run(strconv.Itoa(int(kind))+"/"+strconv.Quote(raw), func(t *testing.T) {
				if err := ValidateTransactionScheduleFrequency(kind, raw); err == nil {
					t.Fatal("corrupt frequency must not be silently normalized")
				}
			})
		}
	}
}

func templateLedgerString(value string) *string { return &value }

func templateRequestLedger(t *testing.T, dto any) int64 {
	t.Helper()
	field := reflect.ValueOf(dto).Elem().FieldByName("LedgerId")
	if !field.IsValid() || field.Kind() != reflect.Int64 {
		t.Fatal("request ledgerId must be an int64")
	}
	return field.Int()
}
