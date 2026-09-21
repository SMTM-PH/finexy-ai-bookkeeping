package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.finexy.mobile.data.AccountEntity
import com.finexy.mobile.data.LedgerEntity
import com.finexy.mobile.data.SecureStore
import com.finexy.mobile.data.TagEntity

class ActivityFilterQaActivity : PrivacyActivity() {
    override fun privacyStore() = SecureStore(applicationContext, "activity-filter-ui-fixture")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val now = System.currentTimeMillis()
        val rows = listOf(
            Activity("工作午餐", "-¥ 20.00", "支出", "餐饮", "a", accountId = 10, tagIdsJson = "[\"30\"]", time = now),
            Activity("私人午餐", "-¥ 15.00", "支出", "餐饮", "b", accountId = 10, tagIdsJson = "[\"31\"]", time = now),
            Activity("工资到账", "+¥ 8,000.00", "收入", "工资", "c", accountId = 11, tagIdsJson = "[\"30\"]", time = now),
            Activity("历史工作午餐", "-¥ 10.00", "支出", "餐饮", "d", accountId = 10, tagIdsJson = "[\"30\"]", time = now - 120 * 86_400_000L)
        )
        setContent { FinexyTheme(true) {
            var selectedLedgerId by remember { mutableLongStateOf(0L) }
            Scaffold(containerColor = CanvasBlack, topBar = {
                LedgerSwitcher(listOf(LedgerEntity(9, 100, LedgerEntity.TYPE_FAMILY, 7, "温暖小家")), selectedLedgerId) { selectedLedgerId = it }
            }) { padding ->
            ActivityScreen(padding, rows, listOf(AccountEntity(10, "日常银行卡", "CNY"), AccountEntity(11, "工资卡", "CNY")), listOf(TagEntity(30, "工作", 0), TagEntity(31, "私人", 0)), {}, {}, readOnly = selectedLedgerId > 0)
        } } }
    }
}
