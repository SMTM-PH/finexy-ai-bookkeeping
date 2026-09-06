package com.finexy.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class DockerSecurityE2ETest {
    @Test fun registrationProfileRefreshSessionsPasswordAndTwoFactorLifecycle() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("finexy.e2e.url")
        assumeTrue("Use only an isolated disposable Docker server", !url.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "security-e2e-${UUID.randomUUID()}"
        val otherName = "$name-other"
        val store = SecureStore(context, name); val otherStore = SecureStore(context, otherName)
        val user = "security" + UUID.randomUUID().toString().replace("-", "").take(14)
        val password = UUID.randomUUID().toString(); val newPassword = UUID.randomUUID().toString()
        try {
            store.put(FinexyApi.KEY_SERVER_URL, url!!, durable = true)
            otherStore.put(FinexyApi.KEY_SERVER_URL, url, durable = true)
            AccountSecurity(FinexyApi(store)).register(user, "$user@example.com", "Security Test", password, "CNY")
            val login = FinexyApi(store).login(user, password)
            assertFalse(login.need2FA)
            store.put(FinexyApi.KEY_TOKEN, login.token, durable = true)
            val api = FinexyApi(store); val security = AccountSecurity(api)
            val identity = LedgerScope.identity(url, login.token, false)
            assertEquals(user, security.profile().getString("username"))
            assertEquals("Updated", security.updateProfile(JSONObject().put("nickname", "Updated")).getString("nickname"))
            api.refreshSession()
            assertEquals(identity, LedgerScope.identity(url, store.get(FinexyApi.KEY_TOKEN), false))
            val otherLogin = FinexyApi(otherStore).login(user, password)
            otherStore.put(FinexyApi.KEY_TOKEN, otherLogin.token, durable = true)
            var sessions = security.sessions()
            val others = (0 until sessions.length()).map(sessions::getJSONObject).filterNot { it.getBoolean("isCurrent") }
            assertTrue(others.isNotEmpty())
            security.revoke(others.first().getString("tokenId"))
            sessions = security.sessions()
            assertFalse((0 until sessions.length()).any { sessions.getJSONObject(it).getString("tokenId") == others.first().getString("tokenId") })
            security.revokeOthers()
            assertTrue((0 until security.sessions().length()).all { security.sessions().getJSONObject(it).getBoolean("isCurrent") })

            assertFalse(security.twoFactorEnabled())
            val secret = security.requestTwoFactor()
            val codes = security.enableTwoFactor(secret, totp(secret))
            assertTrue(codes.isNotEmpty()); assertTrue(security.twoFactorEnabled())
            val challenge = FinexyApi(otherStore).login(user, password)
            assertTrue(challenge.need2FA)
            val verified = AccountSecurity(FinexyApi(otherStore)).completeTwoFactor(challenge.token, totp(secret), false)
            assertFalse(verified.need2FA)
            otherStore.put(FinexyApi.KEY_TOKEN, verified.token, durable = true)
            assertEquals(user, AccountSecurity(FinexyApi(otherStore)).profile().getString("username"))
            val recoveryChallenge = FinexyApi(otherStore).login(user, password)
            val recovered = AccountSecurity(FinexyApi(otherStore)).completeTwoFactor(recoveryChallenge.token, codes.first(), true)
            assertFalse(recovered.need2FA)
            val repeatChallenge = FinexyApi(otherStore).login(user, password)
            var reusedRejected = false
            try { AccountSecurity(FinexyApi(otherStore)).completeTwoFactor(repeatChallenge.token, codes.first(), true) } catch (_: ApiException) { reusedRejected = true }
            assertTrue(reusedRejected)
            val newCodes = security.regenerateCodes(password)
            assertTrue(newCodes.isNotEmpty()); assertNotEquals(codes, newCodes)
            security.disableTwoFactor(password); assertFalse(security.twoFactorEnabled())
            assertFalse(FinexyApi(otherStore).login(user, password).need2FA)

            var wrongPasswordRejected = false
            try { security.updateProfile(JSONObject().put("oldPassword", "wrong-password").put("password", newPassword)) } catch (_: ApiException) { wrongPasswordRejected = true }
            assertTrue(wrongPasswordRejected)
            security.updateProfile(JSONObject().put("oldPassword", password).put("password", newPassword))
            val changedLogin = FinexyApi(otherStore).login(user, newPassword)
            assertTrue(changedLogin.token.isNotBlank())
            // Password changes can revoke previous sessions. Log out a fresh, known session.
            otherStore.put(FinexyApi.KEY_TOKEN, changedLogin.token, durable = true)
            val logoutApi = FinexyApi(otherStore); AccountSecurity(logoutApi).logout()
            var revoked = false
            try { AccountSecurity(logoutApi).profile() } catch (_: ApiException) { revoked = true }
            assertTrue(revoked)
        } finally { context.deleteSharedPreferences(name); context.deleteSharedPreferences(otherName) }
    }

    /** RFC 6238 fixture only; the application delegates OTP generation to the user's authenticator. */
    private fun totp(secret: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0; var bits = 0; val bytes = java.io.ByteArrayOutputStream()
        secret.forEach { ch -> buffer = (buffer shl 5) or alphabet.indexOf(ch); bits += 5; if (bits >= 8) { bits -= 8; bytes.write((buffer shr bits) and 255) } }
        val counter = java.nio.ByteBuffer.allocate(8).putLong(System.currentTimeMillis() / 30_000).array()
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(bytes.toByteArray(), "HmacSHA1")) }.doFinal(counter)
        val offset = mac.last().toInt() and 15
        val number = ((mac[offset].toInt() and 127) shl 24) or ((mac[offset + 1].toInt() and 255) shl 16) or ((mac[offset + 2].toInt() and 255) shl 8) or (mac[offset + 3].toInt() and 255)
        return (number % 1_000_000).toString().padStart(6, '0')
    }
}
