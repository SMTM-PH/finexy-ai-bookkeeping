import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';

// Synthetic state via real APIs only. Caller owns the disposable service and
// keeps authentication in memory. No browser journal or localStorage writes.
export async function seedLedgerPendingWebFixture(api, password, runCron) {
    const register = async label => {
        const username = `pending_${randomBytes(7).toString('hex')}`;
        const result = await api('register.json', null, { username, password, email: `${username}@example.invalid`,
            nickname: `Synthetic pending ${label}`, language: 'zh-Hans', defaultCurrency: 'CNY', categories: [] });
        assert.equal(typeof result.token, 'string'); return { username, token: result.token };
    };
    const owner = await register('owner'), admin = await register('admin'), member = await register('member'), viewer = await register('viewer');
    const suffix = randomBytes(5).toString('hex');
    const ledgerA = await api('v1/ledger/create.json', owner, { type: 1, name: `Synthetic pending A ${suffix}` });
    const ledgerB = await api('v1/ledger/create.json', owner, { type: 1, name: `Synthetic pending B ${suffix}` });
    const personal = { id: '0', name: '默认个人账本' };
    const memberIDs = new Map();
    for (const [actor, role] of [[admin, 2], [member, 3], [viewer, 4]]) {
        const invitation = await api('v1/ledger/invitation/create.json', owner, { ledgerId: ledgerA.id, inviteeName: actor.username, role: role === 2 ? 3 : role, expiresInSeconds: 86400 });
        await api('v1/ledger/invitation/accept.json', actor, { token: invitation.token });
        const me = (await api(`v1/ledger/member/list.json?ledgerId=${ledgerA.id}`, actor)).find(row => row.isCurrentUser);
        assert(me); memberIDs.set(actor, me.id);
        if (role === 2) await api('v1/ledger/member/change_role.json', owner, { ledgerId: ledgerA.id, memberId: me.id, role });
    }
    const accounts = new Map();
    for (const ledger of [personal, ledgerA, ledgerB]) accounts.set(ledger.id, await api('v1/accounts/add.json', owner, {
        ledgerId: ledger.id, name: `Synthetic ${ledger.id === '0' ? 'personal' : ledger.id === ledgerA.id ? 'A' : 'B'} CNY`,
        category: 2, type: 1, icon: '1', color: '000000', currency: 'CNY', balance: 10000, balanceTime: 946684800
    }));
    const categoryRoot = await api('v1/transaction/categories/add.json', owner, { name: 'Synthetic pending root', type: 2, icon: '1', color: '000000' });
    const category = await api('v1/transaction/categories/add.json', owner, { name: 'Synthetic pending expense', type: 2, parentId: categoryRoot.id, icon: '1', color: '000000' });
    const createAI = ledgerId => api('v1/ai/review/create.json', owner, { ledgerId, sourceType: 1, sourceText: 'Synthetic unconfirmed pending evidence',
        clientSessionId: randomBytes(16).toString('hex') });
    const aiA = [await createAI(ledgerA.id), await createAI(ledgerA.id)];
    const aiB = [await createAI(ledgerB.id)], aiPersonal = [await createAI('0')];
    const confirmedAI = await createAI(ledgerA.id);
    const confirmation = await api('v1/ai/review/confirm.json', owner, { ledgerId: ledgerA.id, id: confirmedAI.id, transaction: {
        ledgerId: ledgerA.id, clientSessionId: randomBytes(16).toString('hex'), type: 3, time: Math.floor(Date.now() / 1000), utcOffset: 0,
        categoryId: category.id, sourceAccountId: accounts.get(ledgerA.id).id, destinationAccountId: '0', sourceAmount: 123,
        destinationAmount: 0, tagIds: [], pictureIds: [], hideAmount: false, comment: 'Synthetic already posted row'
    } });
    assert.equal(typeof confirmation.transactionId, 'string');
    const utc = new Date(), minute = utc.getUTCHours() * 60 + utc.getUTCMinutes(), offset = minute <= 720 ? -minute : 1440 - minute;
    const templates = [];
    for (const amount of [250, -100]) templates.push(await api('v1/transaction/templates/add.json', owner, {
        ledgerId: ledgerA.id, templateType: 2, name: `Synthetic pending due ${amount < 0 ? 'refund' : 'expense'}`,
        type: 3, categoryId: category.id, sourceAccountId: accounts.get(ledgerA.id).id, destinationAccountId: '0', sourceAmount: amount,
        destinationAmount: 0, utcOffset: offset, hideAmount: false, tagIds: [], comment: '', scheduledFrequencyType: 3,
        scheduledFrequency: '0', scheduledStartDate: '2020-01-01', clientSessionId: randomBytes(16).toString('hex')
    }));
    await runCron();
    const occurrences = await api(`v1/schedule/review/list.json?ledgerId=${ledgerA.id}&status=1&offset=0&limit=100`, owner);
    assert.equal(occurrences.length, 2); assert(occurrences.every(row => templates.some(template => template.id === row.templateId)));
    const balances = new Map();
    for (const ledger of [personal, ledgerA, ledgerB]) balances.set(ledger.id, (await api(`v1/accounts/get.json?ledgerId=${ledger.id}&id=${accounts.get(ledger.id).id}`, owner)).balance);
    return { owner, admin, member, viewer, ledgerA, ledgerB, personal, memberIDs, accounts, categoryRoot, category,
        aiA, aiB, aiPersonal, confirmedAI, confirmation, occurrences, balances };
}
