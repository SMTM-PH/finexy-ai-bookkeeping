import { ScheduledTemplateFrequencyType } from '@/core/template.ts';
import type { TransactionTemplate } from '@/models/transaction_template.ts';

const weekdayNames = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

export function formatScheduledRule(template: TransactionTemplate): string {
    const values = (template.scheduledFrequency || '').split(',').map(value => parseInt(value)).filter(value => !Number.isNaN(value));
    if (template.scheduledFrequencyType === ScheduledTemplateFrequencyType.Disabled.type) return '已停用';
    if (template.scheduledFrequencyType === ScheduledTemplateFrequencyType.Daily.type) return '每天';
    if (template.scheduledFrequencyType === ScheduledTemplateFrequencyType.EveryNDays.type) return values.length ? `每 ${values[0]} 天` : '按天数重复';
    if (template.scheduledFrequencyType === ScheduledTemplateFrequencyType.Weekly.type) {
        return values.length ? `每周 ${values.map(value => weekdayNames[value] || value).join('、')}` : '每周';
    }
    if (template.scheduledFrequencyType === ScheduledTemplateFrequencyType.Monthly.type) {
        const days = values.map(value => value === -1 ? '最后一天' : (value < 0 ? `倒数第 ${Math.abs(value)} 天` : `${value} 日`));
        return days.length ? `每月 ${days.join('、')}` : '每月';
    }
    if (template.scheduledFrequencyType === ScheduledTemplateFrequencyType.Yearly.type) {
        const days = values.map(value => `${Math.floor(value / 100)} 月 ${value % 100} 日`);
        return days.length ? `每年 ${days.join('、')}` : '每年';
    }
    return '周期规则未设置';
}

export function formatNextScheduledTime(unixTime?: number): string {
    if (!unixTime) return '无后续执行时间';
    return new Date(unixTime * 1000).toLocaleString('zh-CN', {
        year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false
    });
}
