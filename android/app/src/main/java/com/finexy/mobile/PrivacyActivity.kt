package com.finexy.mobile

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import com.finexy.mobile.data.AppLock
import com.finexy.mobile.data.SecureStore

/** Shared lifecycle gate. Debug UI tests override only the store, never authentication behavior. */
open class PrivacyActivity : ComponentActivity() {
    private var locked by mutableStateOf(false)
    val isPrivacyLocked: Boolean get() = locked
    private lateinit var appLock: AppLock
    private var sensitiveScreens = 0
    protected open fun privacyStore(): SecureStore = SecureStore(applicationContext)

    fun refreshLockSettings() {
        if (!appLock.enabled) locked = false
        if (appLock.enabled || sensitiveScreens > 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun enterSensitiveScreen() { sensitiveScreens++; refreshLockSettings() }
    fun leaveSensitiveScreen() { sensitiveScreens = (sensitiveScreens - 1).coerceAtLeast(0); refreshLockSettings() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appLock = AppLock(privacyStore())
        locked = appLock.enabled
        refreshLockSettings()
    }

    override fun onStop() {
        if (::appLock.isInitialized && appLock.enabled) locked = true
        super.onStop()
    }

    @Composable
    protected fun LockOverlay() { if (locked) AppLockDialog(appLock) { locked = false } }
}
