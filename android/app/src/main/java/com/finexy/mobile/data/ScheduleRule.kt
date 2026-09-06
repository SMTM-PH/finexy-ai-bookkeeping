package com.finexy.mobile.data

import java.time.LocalDate
import java.time.MonthDay

/** Pausing blanks the frequency but preserves it locally so resume can restore. */
internal fun TemplateEntity.pausedSchedule(): TemplateEntity {
    require(templateType == 2) { "只能暂停周期计划" }
    require(scheduledFrequencyType != 0) { "周期计划已处于暂停状态" }
    return copy(pausedFromFrequencyType = scheduledFrequencyType, pausedFromFrequency = scheduledFrequency,
        scheduledFrequencyType = 0, scheduledFrequency = "", nextScheduledTime = null)
}

/** Restores the frequency captured when the schedule was paused. */
internal fun TemplateEntity.resumedSchedule(): TemplateEntity {
    require(templateType == 2 && scheduledFrequencyType == 0) { "只能恢复已暂停的周期计划" }
    val frequencyType = pausedFromFrequencyType ?: throw IllegalArgumentException("缺少暂停前的周期规则，请编辑后重新设置")
    val frequency = pausedFromFrequency ?: throw IllegalArgumentException("缺少暂停前的周期规则，请编辑后重新设置")
    return copy(scheduledFrequencyType = frequencyType, scheduledFrequency = frequency,
        pausedFromFrequencyType = null, pausedFromFrequency = null)
}

/** Validates the server's calendar encoding before any scheduled-template write. */
internal fun TemplateEntity.validateSchedule() {
    if (templateType != 2) return
    require(utcOffset != null && utcOffset in -720..840) { "请选择有效的计划时区" }
    val frequency = scheduledFrequency ?: error("请选择周期规则")
    val values = if (frequency.isEmpty()) emptyList() else frequency.split(',').map {
        it.toIntOrNull() ?: throw IllegalArgumentException("周期规则包含无效数字")
    }
    val valid = when (scheduledFrequencyType) {
        0 -> values.isEmpty()
        1 -> values.isNotEmpty() && values.all { it in 0..6 }
        2 -> values.isNotEmpty() && values.all { it in 1..31 || it in -31..-1 }
        3 -> values.isNotEmpty()
        4 -> values.isNotEmpty() && values.all { runCatching { MonthDay.of(it / 100, it % 100) }.isSuccess }
        5 -> values.size == 1 && values.single() > 0 && !scheduledStartDate.isNullOrBlank()
        else -> false
    }
    require(valid) { "周期规则无效；每 N 天重复必须设置开始日期" }
    fun parseDate(value: String?): LocalDate? = value?.let {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(it)) { "日期格式应为 YYYY-MM-DD" }
        runCatching { LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("日期无效") }
    }
    val start = parseDate(scheduledStartDate)
    val end = parseDate(scheduledEndDate)
    require(start == null || end == null || !start.isAfter(end)) { "结束日期不能早于开始日期" }
}
