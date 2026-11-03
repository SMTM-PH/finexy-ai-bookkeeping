package services

import (
	"bytes"
	"encoding/base64"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"image"
	"image/png"
	"strings"
	"testing"
)

func cardTestImage() string {
	var data bytes.Buffer
	_ = png.Encode(&data, image.NewNRGBA(image.Rect(0, 0, 2, 2)))
	return "data:image/png;base64," + base64.StdEncoding.EncodeToString(data.Bytes())
}

func TestAccountCardRejectsUnsafeOrInvalidImages(t *testing.T) {
	valid := models.DefaultAccountCardStyle()
	valid.Background = cardTestImage()
	valid.Subject = valid.Background
	if err := ValidateAccountCardStyle(valid); err != nil {
		t.Fatal(err)
	}
	for _, value := range []string{"https://example.invalid/picture.png", "data:image/svg+xml;base64,PHN2Zz4=", "data:image/png;base64,YWJj", strings.Replace(valid.Background, "image/png", "image/jpeg", 1), "data:image/png;base64," + strings.Repeat("a", 700001)} {
		bad := valid
		bad.Background = value
		if ValidateAccountCardStyle(bad) == nil {
			t.Fatal("invalid image accepted")
		}
	}
	for _, change := range []func(*models.AccountCardStyle){func(s *models.AccountCardStyle) { s.Theme = "url(...)" }, func(s *models.AccountCardStyle) { s.ImageX = -1 }, func(s *models.AccountCardStyle) { s.ImageZoom = 201 }, func(s *models.AccountCardStyle) { s.Revision = -1 }} {
		bad := valid
		change(&bad)
		if ValidateAccountCardStyle(bad) == nil {
			t.Fatal("invalid appearance accepted")
		}
	}
}
