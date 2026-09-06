package com.finexy.mobile.data

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleRuleTest {
    private val base = TemplateEntity(1, "计划", 3, 20, 10, 100, "", templateType = 2, utcOffset = 480)

    @Test fun pausingClearsExecutionRuleButStashesFrequencyForResume() {
        val active = base.copy(type = 4, destinationAccountId = 9007199254740993L, destinationAmountMinor = 200,
            scheduledFrequencyType = 5, scheduledFrequency = "7", scheduledStartDate = "2026-09-05", nextScheduledTime = 1789000000L)
        val paused = active.pausedSchedule()
        assertEquals(active.copy(scheduledFrequencyType = 0, scheduledFrequency = "", nextScheduledTime = null,
            pausedFromFrequencyType = 5, pausedFromFrequency = "7"), paused)
        paused.validateSchedule()
        val resumed = paused.resumedSchedule()
        // The next execution time is recomputed by the server after the resume.
        assertEquals(active.copy(nextScheduledTime = null), resumed)
        resumed.validateSchedule()
    }

    @Test fun calendarRulesAcceptLeapDayAndMonthEnd() {
        base.copy(scheduledFrequencyType = 4, scheduledFrequency = "229,1231").validateSchedule()
        base.copy(scheduledFrequencyType = 2, scheduledFrequency = "1,-1").validateSchedule()
        base.copy(scheduledFrequencyType = 5, scheduledFrequency = "7", scheduledStartDate = "2026-09-05").validateSchedule()
        base.copy(scheduledFrequencyType = 0, scheduledFrequency = "").validateSchedule()
    }

    @Test fun malformedRulesAndDateBoundsCannotBeSubmitted() {
        listOf(
            base.copy(scheduledFrequencyType = 4, scheduledFrequency = "230"),
            base.copy(scheduledFrequencyType = 1, scheduledFrequency = "7"),
            base.copy(scheduledFrequencyType = 2, scheduledFrequency = "0"),
            base.copy(scheduledFrequencyType = 5, scheduledFrequency = "7"),
            base.copy(scheduledFrequencyType = 0, scheduledFrequency = "1"),
            base.copy(scheduledFrequencyType = 3, scheduledFrequency = "1", utcOffset = null),
            base.copy(scheduledFrequencyType = 3, scheduledFrequency = "1", scheduledStartDate = "2026-02-30"),
            base.copy(scheduledFrequencyType = 3, scheduledFrequency = "1", scheduledStartDate = "2026-09-06", scheduledEndDate = "2026-09-05")
        ).forEach { rule -> assertThrows(IllegalArgumentException::class.java) { rule.validateSchedule() } }
    }
}
