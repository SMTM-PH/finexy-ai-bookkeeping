import { describe, expect, it } from 'vitest';
import { ScheduledTemplateFrequencyType } from '@/core/template.ts';
import { scheduleDaysInMonth, scheduleMatchesDate } from '@/lib/schedule.ts';

describe('schedule calendar rules', () => {
    it('renders monthly rules and honors start and end dates', () => {
        const rule = {
            scheduledFrequencyType: ScheduledTemplateFrequencyType.Monthly.type,
            scheduledFrequency: '2,15,-1',
            scheduledStartDate: '2026-09-10',
            scheduledEndDate: '2026-10-15'
        };
        expect(scheduleDaysInMonth(rule, 2026, 8)).toEqual([15, 30]);
        expect(scheduleDaysInMonth(rule, 2026, 9)).toEqual([2, 15]);
    });

    it('renders weekly and every-n-days rules', () => {
        expect(scheduleMatchesDate({
            scheduledFrequencyType: ScheduledTemplateFrequencyType.Weekly.type,
            scheduledFrequency: '3'
        }, new Date(2026, 8, 9))).toBe(true);

        const everyTwoDays = {
            scheduledFrequencyType: ScheduledTemplateFrequencyType.EveryNDays.type,
            scheduledFrequency: '2',
            scheduledStartDate: '2026-09-09'
        };
        expect(scheduleMatchesDate(everyTwoDays, new Date(2026, 8, 11))).toBe(true);
        expect(scheduleMatchesDate(everyTwoDays, new Date(2026, 8, 12))).toBe(false);
    });

    it('does not render paused or hidden plans', () => {
        expect(scheduleDaysInMonth({ scheduledFrequencyType: 0, scheduledFrequency: '' }, 2026, 8)).toEqual([]);
        expect(scheduleDaysInMonth({ scheduledFrequencyType: 3, scheduledFrequency: '0', hidden: true }, 2026, 8)).toEqual([]);
    });
});
