import type { TransactionCategory } from '@/models/transaction_category.ts';

export interface CategoryCatalogItem {
    title: string;
    meta: string;
    amount: string;
    status: string;
    tone: 'success' | 'warning';
    icon: string;
    color: string;
    raw: TransactionCategory;
}

/**
 * 把按类型分组的分类拍平。分类结构页需要覆盖全部一级分类，不能为了首屏观感静默截断，
 * 否则新增分类既不会渲染，也搜不到（issue #48）。
 */
export function flattenTransactionCategories(allCategories: Record<number, TransactionCategory[] | undefined>): TransactionCategory[] {
    return Object.values(allCategories).flatMap((items) => items ?? []);
}

export function buildCategoryCatalog(categories: TransactionCategory[]): CategoryCatalogItem[] {
    return categories.map((item) => ({
        title: item.name,
        meta: item.comment || `${item.subCategories?.length || 0} 个子分类`,
        amount: item.visible ? '使用中' : '已隐藏',
        status: item.visible ? '正常' : '已隐藏',
        tone: item.visible ? 'success' : 'warning',
        icon: item.name.slice(0, 1),
        color: String(item.color),
        raw: item,
    }));
}

/** 搜索直接作用于完整目录，查询为空时返回全部条目。 */
export function filterCatalogItems<T extends { title: string; meta: string }>(items: T[], query: string): T[] {
    const keyword = query.trim();
    if (!keyword) {
        return items;
    }
    return items.filter((item) => `${item.title}${item.meta}`.includes(keyword));
}

/** 子分类编辑同样传子分类自身的 id，而不是所属一级分类的 id。 */
export function categoryEditPayload(category: TransactionCategory): { id: string; currentCategory: TransactionCategory } {
    return { id: category.id, currentCategory: category };
}
