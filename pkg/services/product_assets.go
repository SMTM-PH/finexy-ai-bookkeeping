package services

import (
	"time"

	"xorm.io/xorm"

	"github.com/mayswind/ezbookkeeping/pkg/core"
	"github.com/mayswind/ezbookkeeping/pkg/datastore"
	"github.com/mayswind/ezbookkeeping/pkg/errs"
	"github.com/mayswind/ezbookkeeping/pkg/models"
	"github.com/mayswind/ezbookkeeping/pkg/uuid"
)

// ProductAssetService represents the product asset service.
type ProductAssetService struct {
	ServiceUsingDB
	ServiceUsingUuid
}

// ProductAssets is the product asset service singleton.
var ProductAssets = &ProductAssetService{
	ServiceUsingDB:   ServiceUsingDB{container: datastore.Container},
	ServiceUsingUuid: ServiceUsingUuid{container: uuid.Container},
}

// GetAllProductAssetsByUid returns all product assets for a user.
func (s *ProductAssetService) GetAllProductAssetsByUid(c core.Context, uid int64, status models.ProductAssetStatus) ([]*models.ProductAsset, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}

	query := s.UserDataDB(uid).NewSession(c).Where("uid=? AND deleted=?", uid, false)

	if status != 0 {
		query = query.And("status=?", status)
	}

	var assets []*models.ProductAsset
	err := query.OrderBy("purchase_unix_time desc, product_asset_id desc").Find(&assets)
	return assets, err
}

// GetProductAssetById returns one product asset owned by a user.
func (s *ProductAssetService) GetProductAssetById(c core.Context, uid int64, assetId int64) (*models.ProductAsset, error) {
	if uid <= 0 {
		return nil, errs.ErrUserIdInvalid
	}

	if assetId <= 0 {
		return nil, errs.ErrProductAssetIdInvalid
	}

	asset := &models.ProductAsset{}
	has, err := s.UserDataDB(uid).NewSession(c).ID(assetId).Where("uid=? AND deleted=?", uid, false).Get(asset)

	if err != nil {
		return nil, err
	} else if !has {
		return nil, errs.ErrProductAssetNotFound
	}

	return asset, nil
}

// CreateProductAsset saves a new product asset.
func (s *ProductAssetService) CreateProductAsset(c core.Context, asset *models.ProductAsset) error {
	if asset.Uid <= 0 {
		return errs.ErrUserIdInvalid
	}

	asset.ProductAssetId = s.GenerateUuid(uuid.UUID_TYPE_PRODUCT_ASSET)

	if asset.ProductAssetId < 1 {
		return errs.ErrSystemIsBusy
	}

	now := time.Now().Unix()
	asset.Deleted = false
	asset.Status = models.PRODUCT_ASSET_STATUS_ACTIVE
	asset.CreatedUnixTime = now
	asset.UpdatedUnixTime = now

	if asset.ManualMarketValue != nil {
		asset.ManualMarketValueUnixTime = &now
	}

	return s.UserDataDB(asset.Uid).DoTransaction(c, func(sess *xorm.Session) error {
		if err := s.validateProductAsset(sess, asset, 0); err != nil {
			return err
		}

		_, err := sess.Insert(asset)
		return err
	})
}

// ModifyProductAsset updates an existing product asset.
func (s *ProductAssetService) ModifyProductAsset(c core.Context, asset *models.ProductAsset, clearManualMarketValue bool) error {
	if asset.Uid <= 0 {
		return errs.ErrUserIdInvalid
	}

	now := time.Now().Unix()
	asset.UpdatedUnixTime = now

	if asset.ManualMarketValue != nil {
		asset.ManualMarketValueUnixTime = &now
	} else if clearManualMarketValue {
		asset.ManualMarketValueUnixTime = nil
	}

	return s.UserDataDB(asset.Uid).DoTransaction(c, func(sess *xorm.Session) error {
		if err := s.validateProductAsset(sess, asset, asset.ProductAssetId); err != nil {
			return err
		}

		updatedRows, err := sess.ID(asset.ProductAssetId).Cols(
			"source_transaction_id", "category", "name", "brand", "model",
			"purchase_amount", "purchase_unix_time", "purchase_timezone_utc_offset",
			"useful_life_days", "residual_amount", "manual_market_value",
			"manual_market_value_unix_time", "comment", "updated_unix_time",
		).Where("uid=? AND deleted=?", asset.Uid, false).Update(asset)

		if err != nil {
			return err
		} else if updatedRows < 1 {
			return errs.ErrProductAssetNotFound
		}

		return nil
	})
}

// SellProductAsset closes an active asset and links an optional income transaction.
func (s *ProductAssetService) SellProductAsset(c core.Context, asset *models.ProductAsset) error {
	if asset.Uid <= 0 {
		return errs.ErrUserIdInvalid
	}

	if asset.Status == models.PRODUCT_ASSET_STATUS_SOLD {
		return errs.ErrProductAssetAlreadySold
	}

	if asset.SoldUnixTime == nil || *asset.SoldUnixTime < asset.PurchaseUnixTime {
		return errs.ErrProductAssetSaleTimeInvalid
	}

	asset.Status = models.PRODUCT_ASSET_STATUS_SOLD
	asset.UpdatedUnixTime = time.Now().Unix()

	return s.UserDataDB(asset.Uid).DoTransaction(c, func(sess *xorm.Session) error {
		if err := s.validateRelatedTransaction(sess, asset.Uid, asset.SaleTransactionId, models.TRANSACTION_DB_TYPE_INCOME, errs.ErrProductAssetSaleTransactionInvalid); err != nil {
			return err
		}

		updatedRows, err := sess.ID(asset.ProductAssetId).Cols(
			"status", "sale_transaction_id", "sold_amount", "sold_unix_time", "updated_unix_time",
		).Where("uid=? AND deleted=? AND status=?", asset.Uid, false, models.PRODUCT_ASSET_STATUS_ACTIVE).Update(asset)

		if err != nil {
			return err
		} else if updatedRows < 1 {
			return errs.ErrProductAssetNotFound
		}

		return nil
	})
}

// DeleteProductAsset soft deletes a product asset.
func (s *ProductAssetService) DeleteProductAsset(c core.Context, uid int64, assetId int64) error {
	if uid <= 0 {
		return errs.ErrUserIdInvalid
	}

	now := time.Now().Unix()
	updateModel := &models.ProductAsset{Deleted: true, DeletedUnixTime: now, UpdatedUnixTime: now}
	updatedRows, err := s.UserDataDB(uid).NewSession(c).ID(assetId).Cols("deleted", "deleted_unix_time", "updated_unix_time").Where("uid=? AND deleted=?", uid, false).Update(updateModel)

	if err != nil {
		return err
	} else if updatedRows < 1 {
		return errs.ErrProductAssetNotFound
	}

	return nil
}

// DeleteAllProductAssets soft deletes all product assets owned by a user.
func (s *ProductAssetService) DeleteAllProductAssets(c core.Context, uid int64) error {
	if uid <= 0 {
		return errs.ErrUserIdInvalid
	}

	now := time.Now().Unix()
	updateModel := &models.ProductAsset{Deleted: true, DeletedUnixTime: now, UpdatedUnixTime: now}
	_, err := s.UserDataDB(uid).NewSession(c).Cols("deleted", "deleted_unix_time", "updated_unix_time").Where("uid=? AND deleted=?", uid, false).Update(updateModel)
	return err
}

func (s *ProductAssetService) validateProductAsset(sess *xorm.Session, asset *models.ProductAsset, excludedAssetId int64) error {
	if !isValidProductAssetCategory(asset.Category) {
		return errs.ErrProductAssetCategoryInvalid
	}

	if asset.PurchaseUnixTime <= 0 {
		return errs.ErrProductAssetPurchaseTimeInvalid
	}

	if asset.UsefulLifeDays <= 0 {
		return errs.ErrProductAssetUsefulLifeInvalid
	}

	if asset.ResidualAmount < 0 || asset.ResidualAmount > asset.PurchaseAmount {
		return errs.ErrProductAssetResidualAmountInvalid
	}

	if err := s.validateRelatedTransaction(sess, asset.Uid, asset.SourceTransactionId, models.TRANSACTION_DB_TYPE_EXPENSE, errs.ErrProductAssetSourceTransactionInvalid); err != nil {
		return err
	}

	if asset.SourceTransactionId > 0 {
		query := sess.Where("uid=? AND deleted=? AND source_transaction_id=?", asset.Uid, false, asset.SourceTransactionId)

		if excludedAssetId > 0 {
			query = query.And("product_asset_id<>?", excludedAssetId)
		}

		exists, err := query.Exist(&models.ProductAsset{})

		if err != nil {
			return err
		} else if exists {
			return errs.ErrProductAssetSourceTransactionUsed
		}
	}

	return nil
}

func (s *ProductAssetService) validateRelatedTransaction(sess *xorm.Session, uid int64, transactionId int64, expectedType models.TransactionDbType, invalidError error) error {
	if transactionId <= 0 {
		return nil
	}

	transaction := &models.Transaction{}
	has, err := sess.ID(transactionId).Where("uid=? AND deleted=?", uid, false).Get(transaction)

	if err != nil {
		return err
	} else if !has || transaction.Type != expectedType {
		return invalidError
	}

	return nil
}

func isValidProductAssetCategory(category models.ProductAssetCategory) bool {
	return category >= models.PRODUCT_ASSET_CATEGORY_OTHER && category <= models.PRODUCT_ASSET_CATEGORY_APPLIANCE
}
