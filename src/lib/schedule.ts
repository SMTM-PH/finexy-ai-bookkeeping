import { ScheduledTemplateFrequencyType } from '@/core/template.ts';

export interface ScheduleCalendarRule {
    scheduledFrequencyType?: number;
    scheduledFrequency?: string;
    scheduledStartDate?: string;
    scheduledEndDate?: string;
    hidden?: boolean;
}

function dateKey(date: Date): string {
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

function frequencyValues(value?: string): number[] {
    return (value || '')
        .split(',')
        .map(item => Number(item))
        .filter(Number.isInteger);
}

export function scheduleMatchesDate(rule: ScheduleCalendarRule, date: Date): boolean {
    const frequencyType = rule.scheduledFrequencyType;
    if (rule.hidden || !frequencyType || frequencyType === ScheduledTemplateFrequencyType.Disabled.type) return false;

    const current = dateKey(date);
    if (rule.scheduledStartDate && current < rule.scheduledStartDate) return false;
    if (rule.scheduledEndDate && current > rule.scheduledEndDate) return false;

    const values = frequencyValues(rule.scheduledFrequency);
    if (frequencyType === ScheduledTemplateFrequencyType.Daily.type) return true;
    if (!values.length) return false;

    if (frequencyType === ScheduledTemplateFrequencyType.Weekly.type) {
        return values.includes(date.getDay());
    }
    if (frequencyType === ScheduledTemplateFrequencyType.Monthly.type) {
        const lastDay = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate();
        return values.some(value => date.getDate() === (value < 0 ? lastDay + value + 1 : value));
    }
    if (frequencyType === ScheduledTemplateFrequencyType.Yearly.type) {
        return values.includes((date.getMonth() + 1) * 100 + date.getDate());
    }
    if (frequencyType === ScheduledTemplateFrequencyType.EveryNDays.type) {
        if (!rule.scheduledStartDate || values.length !== 1 || values[0]! <= 0) return false;
        const [year, month, day] = rule.scheduledStartDate.split('-').map(Number);
        if (!year || !month || !day) return false;
        const elapsedDays = Math.floor((Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) - Date.UTC(year, month - 1, day)) / 86_400_000);
        return elapsedDays >= 0 && elapsedDays % values[0]! === 0;
    }
    return false;
}

export function scheduleDaysInMonth(rule: ScheduleCalendarRule, year: number, monthIndex: number): number[] {
    const days = new Date(year, monthIndex + 1, 0).getDate();
    const matches: number[] = [];
    for (let day = 1; day <= days; day++) {
        if (scheduleMatchesDate(rule, new Date(year, monthIndex, day))) matches.push(day);
    }
    return matches;
}
