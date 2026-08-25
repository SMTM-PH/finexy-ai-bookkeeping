package errs

import "net/http"

// Error codes related to product assets.
var (
	ErrProductAssetIdInvalid                = NewNormalError(NormalSubcategoryProductAsset, 0, http.StatusBadRequest, "product asset id is invalid")
	ErrProductAssetNotFound                 = NewNormalError(NormalSubcategoryProductAsset, 1, http.StatusBadRequest, "product asset not found")
	ErrProductAssetCategoryInvalid          = NewNormalError(NormalSubcategoryProductAsset, 2, http.StatusBadRequest, "product asset category is invalid")
	ErrProductAssetStatusInvalid            = NewNormalError(NormalSubcategoryProductAsset, 3, http.StatusBadRequest, "product asset status is invalid")
	ErrProductAssetUsefulLifeInvalid        = NewNormalError(NormalSubcategoryProductAsset, 4, http.StatusBadRequest, "product asset useful life is invalid")
	ErrProductAssetResidualAmountInvalid    = NewNormalError(NormalSubcategoryProductAsset, 5, http.StatusBadRequest, "product asset residual amount is invalid")
	ErrProductAssetPurchaseTimeInvalid      = NewNormalError(NormalSubcategoryProductAsset, 6, http.StatusBadRequest, "product asset purchase time is invalid")
	ErrProductAssetSaleTimeInvalid          = NewNormalError(NormalSubcategoryProductAsset, 7, http.StatusBadRequest, "product asset sale time is invalid")
	ErrProductAssetAlreadySold              = NewNormalError(NormalSubcategoryProductAsset, 8, http.StatusBadRequest, "product asset has already been sold")
	ErrProductAssetSourceTransactionUsed    = NewNormalError(NormalSubcategoryProductAsset, 9, http.StatusBadRequest, "purchase transaction is already linked to another product asset")
	ErrProductAssetSourceTransactionInvalid = NewNormalError(NormalSubcategoryProductAsset, 10, http.StatusBadRequest, "purchase transaction must be an expense transaction")
	ErrProductAssetSaleTransactionInvalid   = NewNormalError(NormalSubcategoryProductAsset, 11, http.StatusBadRequest, "sale transaction must be an income transaction")
)
