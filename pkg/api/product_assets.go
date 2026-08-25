package api

import (
	"time"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/errs"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/log"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/services"
)

// ProductAssetsApi represents product asset API handlers.
type ProductAssetsApi struct {
	productAssets *services.ProductAssetService
}

// ProductAssets is the product asset API singleton.
var ProductAssets = &ProductAssetsApi{productAssets: services.ProductAssets}

// ProductAssetListHandler returns product assets for the current user.
func (a *ProductAssetsApi) ProductAssetListHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.ProductAssetListRequest

	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	uid := c.GetCurrentUid()
	assets, err := a.productAssets.GetAllProductAssetsByUid(c, uid, request.Status)

	if err != nil {
		log.Errorf(c, "[product_assets.ProductAssetListHandler] failed to get product assets for user \"uid:%d\", because %s", uid, err.Error())
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}

	responses := make([]*models.ProductAssetInfoResponse, len(assets))
	now := time.Now()

	for i, asset := range assets {
		responses[i] = asset.ToProductAssetInfoResponse(now)
	}

	return responses, nil
}

// ProductAssetGetHandler returns one product asset for the current user.
func (a *ProductAssetsApi) ProductAssetGetHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.ProductAssetGetRequest

	if err := c.ShouldBindQuery(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	asset, err := a.productAssets.GetProductAssetById(c, c.GetCurrentUid(), request.Id)

	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}

	return asset.ToProductAssetInfoResponse(time.Now()), nil
}

// ProductAssetCreateHandler creates a product asset for the current user.
func (a *ProductAssetsApi) ProductAssetCreateHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.ProductAssetCreateRequest

	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	defaults := request.Category.DefaultDepreciation()
	usefulLifeDays := defaults.UsefulLifeDays
	residualAmount := defaults.ResidualAmount

	if request.UsefulLifeDays != nil {
		usefulLifeDays = *request.UsefulLifeDays
	}

	if request.ResidualAmount != nil {
		residualAmount = *request.ResidualAmount
	}

	asset := &models.ProductAsset{
		Uid:                       c.GetCurrentUid(),
		SourceTransactionId:       request.SourceTransactionId,
		Category:                  request.Category,
		Name:                      request.Name,
		Brand:                     request.Brand,
		Model:                     request.Model,
		PurchaseAmount:            request.PurchaseAmount,
		PurchaseUnixTime:          request.PurchaseUnixTime,
		PurchaseTimezoneUtcOffset: request.PurchaseTimezoneUtcOffset,
		UsefulLifeDays:            usefulLifeDays,
		ResidualAmount:            residualAmount,
		ManualMarketValue:         request.ManualMarketValue,
		Comment:                   request.Comment,
	}

	if err := a.productAssets.CreateProductAsset(c, asset); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}

	return asset.ToProductAssetInfoResponse(time.Now()), nil
}

// ProductAssetModifyHandler modifies a product asset for the current user.
func (a *ProductAssetsApi) ProductAssetModifyHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.ProductAssetModifyRequest

	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	existing, err := a.productAssets.GetProductAssetById(c, c.GetCurrentUid(), request.Id)

	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}

	asset := &models.ProductAsset{
		ProductAssetId:            request.Id,
		Uid:                       c.GetCurrentUid(),
		Status:                    existing.Status,
		SourceTransactionId:       request.SourceTransactionId,
		Category:                  request.Category,
		Name:                      request.Name,
		Brand:                     request.Brand,
		Model:                     request.Model,
		PurchaseAmount:            request.PurchaseAmount,
		PurchaseUnixTime:          request.PurchaseUnixTime,
		PurchaseTimezoneUtcOffset: request.PurchaseTimezoneUtcOffset,
		UsefulLifeDays:            request.UsefulLifeDays,
		ResidualAmount:            request.ResidualAmount,
		ManualMarketValue:         request.ManualMarketValue,
		ManualMarketValueUnixTime: existing.ManualMarketValueUnixTime,
		SaleTransactionId:         existing.SaleTransactionId,
		SoldAmount:                existing.SoldAmount,
		SoldUnixTime:              existing.SoldUnixTime,
		Comment:                   request.Comment,
	}

	if err := a.productAssets.ModifyProductAsset(c, asset, request.ClearManualMarketValue); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}

	return asset.ToProductAssetInfoResponse(time.Now()), nil
}

// ProductAssetSellHandler closes a product asset by sale.
func (a *ProductAssetsApi) ProductAssetSellHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.ProductAssetSellRequest

	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	asset, err := a.productAssets.GetProductAssetById(c, c.GetCurrentUid(), request.Id)

	if err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}

	asset.SaleTransactionId = request.SaleTransactionId
	asset.SoldAmount = request.SoldAmount
	asset.SoldUnixTime = &request.SoldUnixTime

	if err := a.productAssets.SellProductAsset(c, asset); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}

	return asset.ToProductAssetInfoResponse(time.Now()), nil
}

// ProductAssetDeleteHandler deletes a product asset for the current user.
func (a *ProductAssetsApi) ProductAssetDeleteHandler(c *core.WebContext) (any, *errs.Error) {
	var request models.ProductAssetDeleteRequest

	if err := c.ShouldBindJSON(&request); err != nil {
		return nil, errs.NewIncompleteOrIncorrectSubmissionError(err)
	}

	if err := a.productAssets.DeleteProductAsset(c, c.GetCurrentUid(), request.Id); err != nil {
		return nil, errs.Or(err, errs.ErrOperationFailed)
	}

	return true, nil
}
