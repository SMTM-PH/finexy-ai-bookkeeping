package api

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestPreviousYearMonth(t *testing.T) {
	value, ok := previousYearMonth(202601)
	assert.True(t, ok)
	assert.Equal(t, int32(202512), value)

	value, ok = previousYearMonth(202608)
	assert.True(t, ok)
	assert.Equal(t, int32(202607), value)

	_, ok = previousYearMonth(202613)
	assert.False(t, ok)
}
