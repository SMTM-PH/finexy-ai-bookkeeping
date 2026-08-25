package models

// LocalOCRResponse is the text extracted by the local OCR sidecar.
type LocalOCRResponse struct {
	Text       string  `json:"text"`
	Confidence float64 `json:"confidence"`
}
