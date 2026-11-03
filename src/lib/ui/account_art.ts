import moon from '@/assets/account-art/moon.webp';
import koi from '@/assets/account-art/koi.webp';
import jade from '@/assets/account-art/jade.webp';
import garden from '@/assets/account-art/garden.webp';

// Visual identity only. This never maps accounts, currencies or transactions.
export function accountArt(name: string, category: number) {
    const normalized = name.normalize('NFKC').toLowerCase();
    const identities = [
        ['icbc', /工商银行|icbc/], ['boc', /中国银行|bank of china/],
        ['psbc', /邮储|邮政储蓄|psbc/], ['bocom', /交通银行|bocom/],
        ['rural', /农村|农商/], ['alipay', /支付宝|余额宝|花呗|alipay/],
        ['wechat', /微信|零钱|wechat/], ['jd', /京东|白条/],
        ['douyin', /抖音|douyin/], ['investment', /证券|基金|指数|债券/],
    ] as const;
    const brand = identities.find(([, rule]) => rule.test(normalized))?.[0] || 'generic';
    const patterns: Record<number, string> = { 1: 'cash', 2: 'vault', 8: 'reserve', 3: 'credit', 4: 'digital', 5: 'debt', 6: 'receivable', 9: 'deposit', 7: 'investment' };
    const artwork = brand === 'wechat' || brand === 'rural' || brand === 'psbc' || brand === 'investment' || category === 7 ? 'jade'
        : brand === 'alipay' || brand === 'douyin' || category === 3 || category === 4 || category === 5 ? 'moon' : 'koi';
    return { brand, artwork, pattern: patterns[category] || 'vault', background: garden, subject: { moon, koi, jade }[artwork] };
}
