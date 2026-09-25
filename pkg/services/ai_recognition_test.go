package services

import "testing"

func TestSupportedAIRecognitionImageContentTypes(t *testing.T) {
	for _, contentType := range []string{"image/jpeg", "image/png", "image/gif", "image/webp", " IMAGE/JPEG "} {
		if !isSupportedAIRecognitionImageContentType(contentType) {
			t.Errorf("expected %q to be supported", contentType)
		}
	}

	for _, contentType := range []string{"", "image/svg+xml", "text/plain", "application/octet-stream"} {
		if isSupportedAIRecognitionImageContentType(contentType) {
			t.Errorf("expected %q to be rejected", contentType)
		}
	}
}
