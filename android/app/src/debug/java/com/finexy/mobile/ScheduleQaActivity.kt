package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.room.Room
import com.finexy.mobile.data.FinexyDatabase
import com.finexy.mobile.data.RemoteOccurrence
import com.finexy.mobile.data.SecureStore
import com.finexy.mobile.data.TemplateEntity
import com.finexy.mobile.data.TransactionRepository

/** Non-exported QA host for the schedule review and plan management pages. */
class ScheduleQaActivity : PrivacyActivity() {
    private val database by lazy {
        Room.inMemoryDatabaseBuilder(applicationContext, FinexyDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    private val repository by lazy { TransactionRepository(applicationContext, database, importLegacy = false) }
    var reviewMode by mutableStateOf(true)

    override fun privacyStore() = SecureStore(applicationContext, "schedule-ui-fixture")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kotlinx.coroutines.runBlocking {
            repository.saveAccounts(listOf(
                com.finexy.mobile.data.AccountEntity(10, "现金", "CNY"),
                com.finexy.mobile.data.AccountEntity(11, "储蓄", "CNY")))
            repository.saveCategories(listOf(
                com.finexy.mobile.data.CategoryEntity(201, "房租", 200, 2),
                com.finexy.mobile.data.CategoryEntity(202, "工资", 200, 1)))
            repository.replaceOccurrences(listOf(
                RemoteOccurrence(42, 1_788_700_000, 1, 0, "房租", 3, 201, 10, 0, 420000, 0, 480, false, "[]", "九月房租"),
                RemoteOccurrence(43, 1_788_710_000, 3, 0, "订阅", 3, 201, 10, 0, 1500, 0, 480, false, "[]", "会员")))
            repository.saveTemplates(listOf(
                TemplateEntity(42, "房租计划", 3, 201, 10, 420000, "月租", templateType = 2, scheduledFrequencyType = 2,
                    scheduledFrequency = "1,15", utcOffset = 480, scheduledStartDate = "2026-09-01"),
                TemplateEntity(43, "停用订阅", 3, 201, 10, 1500, "", templateType = 2, scheduledFrequencyType = 0,
                    scheduledFrequency = "", pausedFromFrequencyType = 3, pausedFromFrequency = "")))
        }
        setContent {
            FinexyTheme(true) {
                Scaffold(containerColor = CanvasBlack) { padding ->
                    Column(Modifier.padding(padding)) { ScheduleHost() }
                }
            }
        }
    }

    @Composable
    private fun ScheduleHost() {
        if (reviewMode) OccurrenceReviewPage(privacyStore(), repository, localMode = false, onBack = {})
        else SchedulePlanPage(privacyStore(), repository, localMode = false, onBack = {})
    }
}
