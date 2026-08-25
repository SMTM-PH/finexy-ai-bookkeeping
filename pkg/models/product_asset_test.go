package models

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
)

func TestProductAssetCategoryDefaultDepreciation(t *testing.T) {
	assert.Equal(t, int32(4*365), PRODUCT_ASSET_CATEGORY_PHONE.DefaultDepreciation().UsefulLifeDays)
	assert.Equal(t, int64(0), PRODUCT_ASSET_CATEGORY_PHONE.DefaultDepreciation().ResidualAmount)
	assert.Equal(t, int32(5*365), ProductAssetCategory(255).DefaultDepreciation().UsefulLifeDays)
}

func TestProductAssetValuationAtForActiveAsset(t *testing.T) {
	timezone := time.FixedZone("Asia/Shanghai", 8*60*60)
	purchaseTime := time.Date(2026, time.January, 1, 12, 0, 0, 0, timezone).Unix()
	marketValue := int64(480000)
	asset := &ProductAsset{
		Status:                    PRODUCT_ASSET_STATUS_ACTIVE,
		PurchaseAmount:            600000,
		PurchaseUnixTime:          purchaseTime,
		PurchaseTimezoneUtcOffset: 8 * 60,
		UsefulLifeDays:            4 * 365,
		ResidualAmount:            0,
		ManualMarketValue:         &marketValue,
	}

	valuation := asset.ValuationAt(time.Date(2026, time.January, 10, 22, 0, 0, 0, timezone))

	assert.Equal(t, int32(10), valuation.HeldDays)
	assert.InDelta(t, float64(600000)/float64(4*365), valuation.DailyDepreciation, 0.001)
	assert.Equal(t, int64(4110), valuation.AccumulatedDepreciation)
	assert.Equal(t, int64(595890), valuation.BookValue)
	assert.Equal(t, &marketValue, valuation.MarketValue)
	assert.Equal(t, float64(60000), valuation.AverageDailyCost)
}

func TestProductAssetValuationAtForSoldAsset(t *testing.T) {
	timezone := time.FixedZone("Asia/Shanghai", 8*60*60)
	purchaseTime := time.Date(2026, time.January, 1, 12, 0, 0, 0, timezone).Unix()
	soldTime := time.Date(2026, time.January, 10, 9, 0, 0, 0, timezone).Unix()
	asset := &ProductAsset{
		Status:                    PRODUCT_ASSET_STATUS_SOLD,
		PurchaseAmount:            600000,
		PurchaseUnixTime:          purchaseTime,
		PurchaseTimezoneUtcOffset: 8 * 60,
		UsefulLifeDays:            4 * 365,
		ResidualAmount:            0,
		SoldAmount:                450000,
		SoldUnixTime:              &soldTime,
	}

	valuation := asset.ValuationAt(time.Date(2026, time.February, 1, 0, 0, 0, 0, timezone))

	assert.Equal(t, int32(10), valuation.HeldDays)
	assert.Equal(t, float64(15000), valuation.AverageDailyCost)
}

func TestProductAssetValuationAtNeverDropsBelowResidualAmount(t *testing.T) {
	timezone := time.FixedZone("Asia/Shanghai", 8*60*60)
	asset := &ProductAsset{
		Status:                    PRODUCT_ASSET_STATUS_ACTIVE,
		PurchaseAmount:            600000,
		PurchaseUnixTime:          time.Date(2020, time.January, 1, 0, 0, 0, 0, timezone).Unix(),
		PurchaseTimezoneUtcOffset: 8 * 60,
		UsefulLifeDays:            365,
		ResidualAmount:            100000,
	}

	valuation := asset.ValuationAt(time.Date(2026, time.January, 1, 0, 0, 0, 0, timezone))

	assert.Equal(t, int64(500000), valuation.AccumulatedDepreciation)
	assert.Equal(t, int64(100000), valuation.BookValue)
}
