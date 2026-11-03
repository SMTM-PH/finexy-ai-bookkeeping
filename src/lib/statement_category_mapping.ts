import { TransactionType } from '@/core/transaction.ts';
import type { ImportTransaction } from '@/models/imported_transaction.ts';
import type { TransactionCategory } from '@/models/transaction_category.ts';

export interface CategorySuggestion {
    key: string;
    name: string;
    type: number;
    targetId: string;
    reason: string;
}

// Suggestions only: applying a mapping and committing transactions are separate actions.
const expenseRules = [
    { name: '餐饮', aliases: ['餐饮', '餐饮美食', '餐饮消费', '食品', '吃喝'], words: ['餐饮', '餐厅', '食堂', '外卖', '早餐', '午餐', '晚餐', '快餐', '小吃', '面馆', '火锅'] },
    { name: '饮品', aliases: ['饮品', '饮料', '咖啡奶茶'], words: ['奶茶', '咖啡', '饮料', '茶饮'] },
    { name: '交通', aliases: ['交通', '交通出行', '公共交通', '公交地铁'], words: ['公交', '地铁', '乘车', '车票', '高铁', '火车票'] },
    { name: '打车', aliases: ['打车', '出租车', '网约车'], words: ['打车', '出租车', '网约车', '滴滴出行'] },
    { name: '日用', aliases: ['日用', '日用百货', '生活用品', '日用品'], words: ['日用', '百货', '纸巾', '洗衣液', '洗洁精', '牙膏'] },
    { name: '购物', aliases: ['购物', '购物消费', '服饰', '服饰装扮'], words: ['服饰', '衣服', '鞋子', '裤子'] },
    { name: '通信', aliases: ['通信', '话费', '通讯', '手机话费'], words: ['话费', '流量充值', '宽带', '通信费'] },
    { name: '生活缴费', aliases: ['生活缴费', '水电燃气', '水电费'], words: ['电费', '水费', '燃气费', '物业费'] },
    { name: '医疗', aliases: ['医疗', '医疗健康', '医药', '药品'], words: ['挂号', '药品', '药房', '医药费', '诊疗'] },
    { name: '文娱', aliases: ['文娱', '文化休闲', '娱乐', '休闲娱乐'], words: ['电影票', '视频会员', '游戏充值', '音乐会员'] },
    { name: '教育', aliases: ['教育', '教育培训', '学习', '学费'], words: ['学费', '培训费', '课程费', '教材'] },
] as const;
const genericNames = new Set(['', '/', '其他', '未分类', '商户消费', '消费', '二维码付款', '扫二维码付款']);
const normalize = (value: string) => value.trim().replace(/\s+/g, '').toLowerCase();

export function availableStatementCategories(categories: Record<string, TransactionCategory>, type: number): TransactionCategory[] {
    return Object.values(categories).filter(c => c.type === type && !c.hidden && c.parentId !== '0' &&
        categories[c.parentId]?.type === type && !categories[c.parentId]?.hidden);
}

export function suggestStatementCategory(row: ImportTransaction, categories: Record<string, TransactionCategory>, remembered: Record<string, string> = {}): CategorySuggestion {
    const type = row.type === TransactionType.Income ? 1 : row.type === TransactionType.Expense ? 2 : 3;
    const original = normalize(row.originalCategoryName || '');
    let name = row.originalCategoryName?.trim() || '未标注';
    let rule: typeof expenseRules[number] | undefined;
    if (type === 2) {
        const categoryRules = expenseRules.filter(r => r.aliases.some(alias => normalize(alias) === original));
        if (categoryRules.length === 1) rule = categoryRules[0];
        // Broad payment types do not describe spending purpose. Split only on one clear domain.
        if (genericNames.has(original)) {
            const matches = expenseRules.filter(r => r.words.some(word => row.comment.includes(word)));
            if (matches.length === 1) rule = matches[0];
            if (rule) name = rule.name;
        }
    }
    const key = JSON.stringify([type, original, genericNames.has(original) ? rule?.name || '' : '']);
    const available = availableStatementCategories(categories, type);
    const saved = available.find(c => c.id === remembered[key]);
    if (saved) return { key, name, type, targetId: saved.id, reason: '已确认的关联' };
    const exact = genericNames.has(original) ? [] : available.filter(c => normalize(c.name) === original);
    if (exact.length === 1) return { key, name, type, targetId: exact[0]!.id, reason: '同类型唯一同名分类，待确认' };
    if (exact.length > 1) return { key, name, type, targetId: '', reason: '存在多个同名分类，请选择' };
    const aliases = rule ? available.filter(c => rule!.aliases.some(alias => normalize(alias) === normalize(c.name))) : [];
    return { key, name, type, targetId: aliases.length === 1 ? aliases[0]!.id : '',
        reason: aliases.length === 1 ? (genericNames.has(original) ? '商品或备注关键词建议，待确认' : '分类名称关联建议，待确认')
            : aliases.length > 1 ? '存在多个可关联分类，请选择' : '未找到明确关联，可选择已有分类或新增' };
}

export function statementMappingStorageKey(server: string, username: string, ledgerId: string, fileType: string): string {
    const provider = fileType.startsWith('alipay_') ? 'alipay' : fileType.startsWith('wechat_') ? 'wechat' : fileType;
    return `finexy_statement_categories_v1:${JSON.stringify([server, username, ledgerId, provider])}`;
}

export function readStatementCategoryMappings(storage: Pick<Storage, 'getItem'>, key: string): Record<string, string> {
    try {
        const data: unknown = JSON.parse(storage.getItem(key) || '{}');
        if (!data || typeof data !== 'object' || Array.isArray(data)) return {};
        return Object.fromEntries(Object.entries(data).filter(([name, id]) => name.length <= 300 && typeof id === 'string' && /^[1-9]\d*$/.test(id)).slice(0, 500));
    } catch { return {}; }
}
