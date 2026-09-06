package com.finexy.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.finexy.mobile.data.TagEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class StatisticsFilterTest {
    private val rows = listOf(
        Activity("午餐\"报销", "-¥ 20.00", "支出", "餐饮", "a", accountId = 10, tagIdsJson = "[\"30\"]", time = 2_000),
        Activity("地铁", "-¥ 5.00", "支出", "交通", "b", accountId = 10, tagIdsJson = "[\"31\"]", time = 2_000),
        Activity("工资", "+¥ 8,000.00", "收入", "工资", "c", accountId = 11, tagIdsJson = "[\"30\"]", time = 2_000),
        Activity("旧午餐", "-¥ 10.00", "支出", "餐饮", "d", accountId = 10, tagIdsJson = "[\"30\"]", time = 500)
    )

    @Test fun everyFilterDimensionUsesOneSharedResult() {
        val filtered = filterStatisticsActivities(rows, 1_000, "支出", 10, "餐饮", 30)
        assertEquals(listOf("a"), filtered.map { it.id })
        assertTrue(filterStatisticsActivities(rows, 1_000, "收入", 10, "全部", 0).isEmpty())
    }

    @Test fun csvContainsOnlyFilteredRowsAndEscapesUserText() {
        val filtered = filterStatisticsActivities(rows, 1_000, "支出", 10, "餐饮", 30)
        val csv = statisticsCsv(filtered, listOf(TagEntity(30, "工作", 0)))
        assertTrue(csv.contains("\"午餐\"\"报销\""))
        assertTrue(csv.contains("\"工作\""))
        assertTrue(csv.contains("\"10\""))
        assertFalse(csv.contains("地铁"))
        assertFalse(csv.contains("工资"))
        assertEquals(2, csv.lineSequence().filter(String::isNotBlank).count())
    }

    @Test fun dateRangeAndExportDateUseTheSameLocalTimezoneBoundary() {
        val zone = ZoneId.of("Asia/Shanghai")
        val timeZone = TimeZone.getTimeZone(zone)
        val now = ZonedDateTime.of(2026, 9, 4, 0, 30, 0, 0, zone).toInstant().toEpochMilli()
        val cutoff = statisticsCutoff(7, now, timeZone)
        assertEquals(ZonedDateTime.of(2026, 8, 29, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), cutoff)
        assertEquals("2026-09-04", statisticsDate(now, timeZone))
        val justInside = Activity("边界内", "-¥ 1.00", "支出", "其他", time = cutoff)
        val justOutside = Activity("边界外", "-¥ 1.00", "支出", "其他", time = cutoff - 1)
        assertEquals(listOf("边界内"), filterStatisticsActivities(listOf(justInside, justOutside), cutoff, "全部", 0, "全部", 0).map { it.title })
    }
}
