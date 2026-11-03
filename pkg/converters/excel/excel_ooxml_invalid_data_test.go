package excel

import "testing"

// An unreadable upload must return its parse error without closing a nil workbook.
func TestExcelOOXMLInvalidUploadReturnsError(t *testing.T) {
	for _, input := range []struct {
		name string
		data []byte
	}{
		{name: "empty"},
		{name: "non-workbook", data: []byte("not an Excel workbook")},
		{name: "truncated-zip", data: []byte{'P', 'K', 3, 4}},
	} {
		t.Run(input.name, func(t *testing.T) {
			if table, err := CreateNewExcelOOXMLFileBasicDataTable(input.data, false); err == nil || table != nil {
				t.Fatalf("invalid upload produced table=%v, error=%v", table, err)
			}
			if tables, err := CreateNewExcelOOXMLFileBasicDataTables(input.data, true); err == nil || tables != nil {
				t.Fatalf("invalid upload produced tables=%v, error=%v", tables, err)
			}
		})
	}
}
