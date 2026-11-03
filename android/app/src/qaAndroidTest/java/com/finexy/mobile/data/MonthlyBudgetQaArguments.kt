package com.finexy.mobile.data

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.HttpUrl.Companion.toHttpUrl

internal fun monthlyBudgetQaUrl(): String {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    check(instrumentation.targetContext.packageName == "com.finexy.mobile.qa") { "Budget tests require the isolated QA application" }
    val address = requireNotNull(InstrumentationRegistry.getArguments().getString("finexy.budget.e2e.url")) { "Budget tests require the guarded disposable server argument" }.toHttpUrl()
    require(address.scheme == "http" && address.host == "10.0.2.2" && address.port == 18111 && address.encodedPath == "/" &&
        address.username.isEmpty() && address.password.isEmpty() && address.query == null && address.fragment == null) { "Budget tests require the reserved disposable emulator-host endpoint" }
    return address.toString().trimEnd('/')
}
