//go:build cgo

package services

import (
	"testing"

	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/core"
	"github.com/SMTM-PH/finexy-ai-bookkeeping/pkg/models"
	"github.com/stretchr/testify/require"
)

func TestUserFeatureRestrictionRemovalPreservesOtherBitsAndClearsLastBit(t *testing.T) {
	initFamilyTestEnvironment(t)
	const username = "synthetic-restriction-removal"
	flags := core.UserFeatureRestrictions(0).Add(core.USER_FEATURE_RESTRICTION_TYPE_EXPORT_TRANSACTION).Add(core.USER_FEATURE_RESTRICTION_TYPE_IMPORT_TRANSACTION)
	_, err := Users.UserDB().NewSession(nil).Insert(&models.User{Uid: 77, Username: username, Email: "restriction-removal@example.invalid", FeatureRestriction: flags})
	require.NoError(t, err)
	require.NoError(t, Users.RemoveUserFeatureRestriction(nil, username, core.UserFeatureRestrictions(0).Add(core.USER_FEATURE_RESTRICTION_TYPE_EXPORT_TRANSACTION)))
	user, err := Users.GetUserById(nil, 77)
	require.NoError(t, err)
	require.False(t, user.FeatureRestriction.Contains(core.USER_FEATURE_RESTRICTION_TYPE_EXPORT_TRANSACTION))
	require.True(t, user.FeatureRestriction.Contains(core.USER_FEATURE_RESTRICTION_TYPE_IMPORT_TRANSACTION))
	require.NoError(t, Users.RemoveUserFeatureRestriction(nil, username, core.UserFeatureRestrictions(0).Add(core.USER_FEATURE_RESTRICTION_TYPE_IMPORT_TRANSACTION)))
	user, err = Users.GetUserById(nil, 77)
	require.NoError(t, err)
	require.Equal(t, core.UserFeatureRestrictions(0), user.FeatureRestriction)
	require.NoError(t, Users.AddUserFeatureRestriction(nil, username, core.UserFeatureRestrictions(0).Add(core.USER_FEATURE_RESTRICTION_TYPE_EXPORT_TRANSACTION)))
	user, err = Users.GetUserById(nil, 77)
	require.NoError(t, err)
	require.True(t, user.FeatureRestriction.Contains(core.USER_FEATURE_RESTRICTION_TYPE_EXPORT_TRANSACTION))
}
