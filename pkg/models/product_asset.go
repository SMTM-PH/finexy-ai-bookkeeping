package models

import (
	"math"
	"time"
)

// ProductAssetStatus represents the lifecycle state of a product asset.
type ProductAssetStatus byte

const (
	PRODUCT_ASSET_STATUS_ACTIVE   ProductAssetStatus = 1
	PRODUCT_ASSET_STATUS_SOLD     ProductAssetStatus = 2
	PRODUCT_ASSET_STATUS_DISPOSED ProductAssetStatus = 3
)

// ProductAssetCategory represents a product category used to select depreciation defaults.
type ProductAssetCategory byte

const (
	PRODUCT_ASSET_CATEGORY_OTHER        ProductAssetCategory = 1
	PRODUCT_ASSET_CATEGORY_PHONE        ProductAssetCategory = 2
	PRODUCT_ASSET_CATEGORY_COMPUTER     ProductAssetCategory = 3
	PRODUCT_ASSET_CATEGORY_TABLET       ProductAssetCategory = 4
	PRODUCT_ASSET_CATEGORY_CAMERA       ProductAssetCategory = 5
	PRODUCT_ASSET_CATEGORY_GAME_CONSOLE ProductAssetCategory = 6
	PRODUCT_ASSET_CATEGORY_APPLIANCE    ProductAssetCategory = 7
)

// ProductAssetDepreciationDefaults contains editable defaults selected by product category.
type ProductAssetDepreciationDefaults struct {
	UsefulLifeDays int32 `json:"usefulLifeDays"`
	ResidualAmount int64 `json:"residualAmount"`
}

var productAssetDepreciationDefaults = map[ProductAssetCategory]ProductAssetDepreciationDefaults{
	PRODUCT_ASSET_CATEGORY_OTHER:        {UsefulLifeDays: 5 * 365, ResidualAmount: 0},
	PRODUCT_ASSET_CATEGORY_PHONE:        {UsefulLifeDays: 4 * 365, ResidualAmount: 0},
	PRODUCT_ASSET_CATEGORY_COMPUTER:     {UsefulLifeDays: 5 * 365, ResidualAmount: 0},
	PRODUCT_ASSET_CATEGORY_TABLET:       {UsefulLifeDays: 4 * 365, ResidualAmount: 0},
	PRODUCT_ASSET_CATEGORY_CAMERA:       {UsefulLifeDays: 6 * 365, ResidualAmount: 0},
	PRODUCT_ASSET_CATEGORY_GAME_CONSOLE: {UsefulLifeDays: 5 * 365, ResidualAmount: 0},
	PRODUCT_ASSET_CATEGORY_APPLIANCE:    {UsefulLifeDays: 8 * 365, ResidualAmount: 0},
}

// DefaultDepreciation returns the initial straight-line depreciation settings for a category.
// The returned values are defaults only and may be modified by the user.
func (c ProductAssetCategory) DefaultDepreciation() ProductAssetDepreciationDefaults {
	defaults, exists := productAssetDepreciationDefaults[c]

	if !exists {
		return productAssetDepreciationDefaults[PRODUCT_ASSET_CATEGORY_OTHER]
	}

	return defaults
}

// ProductAsset represents a durable product that retains resale value.
// Monetary values use the same minor currency unit as transactions.
type ProductAsset struct {
	ProductAssetId            int64                `xorm:"PK"`
	Uid                       int64                `xorm:"INDEX(IDX_product_asset_uid_deleted_status_purchase_time) NOT NULL"`
	Deleted                   bool                 `xorm:"INDEX(IDX_product_asset_uid_deleted_status_purchase_time) NOT NULL"`
	SourceTransactionId       int64                `xorm:"INDEX(IDX_product_asset_uid_source_transaction_id)"`
	SaleTransactionId         int64                `xorm:"INDEX(IDX_product_asset_uid_sale_transaction_id)"`
	Category                  ProductAssetCategory `xorm:"NOT NULL"`
	Status                    ProductAssetStatus   `xorm:"INDEX(IDX_product_asset_uid_deleted_status_purchase_time) NOT NULL"`
	Name                      string               `xorm:"VARCHAR(128) NOT NULL"`
	Brand                     string               `xorm:"VARCHAR(64) NOT NULL"`
	Model                     string               `xorm:"VARCHAR(64) NOT NULL"`
	PurchaseAmount            int64                `xorm:"NOT NULL"`
	PurchaseUnixTime          int64                `xorm:"INDEX(IDX_product_asset_uid_deleted_status_purchase_time) NOT NULL"`
	PurchaseTimezoneUtcOffset int16                `xorm:"NOT NULL"`
	UsefulLifeDays            int32                `xorm:"NOT NULL"`
	ResidualAmount            int64                `xorm:"NOT NULL"`
	ManualMarketValue         *int64
	ManualMarketValueUnixTime *int64
	SoldAmount                int64
	SoldUnixTime              *int64
	Comment                   string `xorm:"VARCHAR(255) NOT NULL"`
	CreatedUnixTime           int64
	UpdatedUnixTime           int64
	DeletedUnixTime           int64
}

// ProductAssetValuation is a calculated snapshot and is not stored in the database.
type ProductAssetValuation struct {
	HeldDays                int32   `json:"heldDays"`
	DailyDepreciation       float64 `json:"dailyDepreciation"`
	AccumulatedDepreciation int64   `json:"accumulatedDepreciation"`
	BookValue               int64   `json:"bookValue"`
	MarketValue             *int64  `json:"marketValue,omitempty"`
	AverageDailyCost        float64 `json:"averageDailyCost"`
}

// ProductAssetListRequest represents filters for a product asset list.
type ProductAssetListRequest struct {
	Status ProductAssetStatus `form:"status" binding:"min=0,max=3"`
}

// ProductAssetGetRequest represents the parameters for retrieving one product asset.
type ProductAssetGetRequest struct {
	Id int64 `form:"id,string" binding:"required,min=1"`
}

// ProductAssetCreateRequest represents the parameters for creating a product asset.
type ProductAssetCreateRequest struct {
	SourceTransactionId       int64                `json:"sourceTransactionId,string" binding:"min=0"`
	Category                  ProductAssetCategory `json:"category" binding:"required,min=1,max=7"`
	Name                      string               `json:"name" binding:"required,notBlank,max=128"`
	Brand                     string               `json:"brand" binding:"max=64"`
	Model                     string               `json:"model" binding:"max=64"`
	PurchaseAmount            int64                `json:"purchaseAmount" binding:"required,min=1"`
	PurchaseUnixTime          int64                `json:"purchaseTime" binding:"required,min=1"`
	PurchaseTimezoneUtcOffset int16                `json:"utcOffset" binding:"min=-720,max=840"`
	UsefulLifeDays            *int32               `json:"usefulLifeDays" binding:"omitempty,min=1,max=36500"`
	ResidualAmount            *int64               `json:"residualAmount" binding:"omitempty,min=0"`
	ManualMarketValue         *int64               `json:"manualMarketValue" binding:"omitempty,min=0"`
	Comment                   string               `json:"comment" binding:"max=255"`
}

// ProductAssetModifyRequest represents the parameters for modifying a product asset.
type ProductAssetModifyRequest struct {
	Id                        int64                `json:"id,string" binding:"required,min=1"`
	SourceTransactionId       int64                `json:"sourceTransactionId,string" binding:"min=0"`
	Category                  ProductAssetCategory `json:"category" binding:"required,min=1,max=7"`
	Name                      string               `json:"name" binding:"required,notBlank,max=128"`
	Brand                     string               `json:"brand" binding:"max=64"`
	Model                     string               `json:"model" binding:"max=64"`
	PurchaseAmount            int64                `json:"purchaseAmount" binding:"required,min=1"`
	PurchaseUnixTime          int64                `json:"purchaseTime" binding:"required,min=1"`
	PurchaseTimezoneUtcOffset int16                `json:"utcOffset" binding:"min=-720,max=840"`
	UsefulLifeDays            int32                `json:"usefulLifeDays" binding:"required,min=1,max=36500"`
	ResidualAmount            int64                `json:"residualAmount" binding:"min=0"`
	ManualMarketValue         *int64               `json:"manualMarketValue" binding:"omitempty,min=0"`
	ClearManualMarketValue    bool                 `json:"clearManualMarketValue"`
	Comment                   string               `json:"comment" binding:"max=255"`
}

// ProductAssetSellRequest represents the parameters for closing a product asset by sale.
type ProductAssetSellRequest struct {
	Id                int64 `json:"id,string" binding:"required,min=1"`
	SaleTransactionId int64 `json:"saleTransactionId,string" binding:"min=0"`
	SoldAmount        int64 `json:"soldAmount" binding:"min=0"`
	SoldUnixTime      int64 `json:"soldTime" binding:"required,min=1"`
}

// ProductAssetDeleteRequest represents the parameters for deleting a product asset.
type ProductAssetDeleteRequest struct {
	Id int64 `json:"id,string" binding:"required,min=1"`
}

// ProductAssetInfoResponse represents a product asset and its current valuation.
type ProductAssetInfoResponse struct {
	Id                        int64                 `json:"id,string"`
	SourceTransactionId       int64                 `json:"sourceTransactionId,string,omitempty"`
	SaleTransactionId         int64                 `json:"saleTransactionId,string,omitempty"`
	Category                  ProductAssetCategory  `json:"category"`
	Status                    ProductAssetStatus    `json:"status"`
	Name                      string                `json:"name"`
	Brand                     string                `json:"brand"`
	Model                     string                `json:"model"`
	PurchaseAmount            int64                 `json:"purchaseAmount"`
	PurchaseUnixTime          int64                 `json:"purchaseTime"`
	PurchaseTimezoneUtcOffset int16                 `json:"utcOffset"`
	UsefulLifeDays            int32                 `json:"usefulLifeDays"`
	ResidualAmount            int64                 `json:"residualAmount"`
	ManualMarketValue         *int64                `json:"manualMarketValue,omitempty"`
	ManualMarketValueUnixTime *int64                `json:"manualMarketValueTime,omitempty"`
	SoldAmount                int64                 `json:"soldAmount,omitempty"`
	SoldUnixTime              *int64                `json:"soldTime,omitempty"`
	Comment                   string                `json:"comment"`
	Valuation                 ProductAssetValuation `json:"valuation"`
}

// ToProductAssetInfoResponse returns an API response calculated at the supplied time.
func (a *ProductAsset) ToProductAssetInfoResponse(asOf time.Time) *ProductAssetInfoResponse {
	return &ProductAssetInfoResponse{
		Id:                        a.ProductAssetId,
		SourceTransactionId:       a.SourceTransactionId,
		SaleTransactionId:         a.SaleTransactionId,
		Category:                  a.Category,
		Status:                    a.Status,
		Name:                      a.Name,
		Brand:                     a.Brand,
		Model:                     a.Model,
		PurchaseAmount:            a.PurchaseAmount,
		PurchaseUnixTime:          a.PurchaseUnixTime,
		PurchaseTimezoneUtcOffset: a.PurchaseTimezoneUtcOffset,
		UsefulLifeDays:            a.UsefulLifeDays,
		ResidualAmount:            a.ResidualAmount,
		ManualMarketValue:         a.ManualMarketValue,
		ManualMarketValueUnixTime: a.ManualMarketValueUnixTime,
		SoldAmount:                a.SoldAmount,
		SoldUnixTime:              a.SoldUnixTime,
		Comment:                   a.Comment,
		Valuation:                 a.ValuationAt(asOf),
	}
}

// ValuationAt calculates straight-line depreciation and average daily ownership cost.
// Held days are inclusive, so the purchase date is day one. A sold asset is valued at
// its sale date even when a later as-of time is supplied.
func (a *ProductAsset) ValuationAt(asOf time.Time) ProductAssetValuation {
	timezone := time.FixedZone("Product Asset Timezone", int(a.PurchaseTimezoneUtcOffset)*60)
	purchaseDate := calendarDate(time.Unix(a.PurchaseUnixTime, 0), timezone)
	effectiveDate := calendarDate(asOf, timezone)

	if a.Status == PRODUCT_ASSET_STATUS_SOLD && a.SoldUnixTime != nil {
		soldDate := calendarDate(time.Unix(*a.SoldUnixTime, 0), timezone)

		if soldDate.Before(effectiveDate) {
			effectiveDate = soldDate
		}
	}

	heldDays := int32(0)

	if !effectiveDate.Before(purchaseDate) {
		heldDays = int32(effectiveDate.Sub(purchaseDate)/(24*time.Hour)) + 1
	}

	dailyDepreciation := float64(0)
	accumulatedDepreciation := int64(0)
	bookValue := a.PurchaseAmount

	if heldDays > 0 && a.UsefulLifeDays > 0 && a.PurchaseAmount > a.ResidualAmount {
		dailyDepreciation = float64(a.PurchaseAmount-a.ResidualAmount) / float64(a.UsefulLifeDays)
		depreciatedDays := heldDays

		if depreciatedDays > a.UsefulLifeDays {
			depreciatedDays = a.UsefulLifeDays
		}

		accumulatedDepreciation = int64(math.Round(dailyDepreciation * float64(depreciatedDays)))
		bookValue = a.PurchaseAmount - accumulatedDepreciation

		if bookValue < a.ResidualAmount {
			bookValue = a.ResidualAmount
		}
	}

	averageDailyCost := float64(0)

	if heldDays > 0 {
		ownershipCost := a.PurchaseAmount

		if a.Status == PRODUCT_ASSET_STATUS_SOLD {
			ownershipCost -= a.SoldAmount
		}

		averageDailyCost = float64(ownershipCost) / float64(heldDays)
	}

	return ProductAssetValuation{
		HeldDays:                heldDays,
		DailyDepreciation:       dailyDepreciation,
		AccumulatedDepreciation: accumulatedDepreciation,
		BookValue:               bookValue,
		MarketValue:             a.ManualMarketValue,
		AverageDailyCost:        averageDailyCost,
	}
}

func calendarDate(value time.Time, timezone *time.Location) time.Time {
	localized := value.In(timezone)
	return time.Date(localized.Year(), localized.Month(), localized.Day(), 0, 0, 0, 0, timezone)
}
