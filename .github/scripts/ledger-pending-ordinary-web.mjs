import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { resolve } from 'node:path';
import { writeFile } from 'node:fs/promises';

// This helper shares the caller's verified disposable runtime and synthetic
// account. Every journal is made by the production editor. The two permitted
// fault mechanisms are a real forwarded POST whose response is lost and a
// one-shot native IndexedDB.open failure during the acknowledgement lock.
export async function verifyOrdinaryPendingWeb({ browser, base, api, seed, password, output, pass, step, login,
    changeLedger, home, refresh, counts, homePanel, mobileNavigate, readOnlyStart, readOnlyEnd, notifications, closeNotifications }) {
    void browser; void password; void closeNotifications;
    const ledger = await api('v1/ledger/create.json', seed.owner, { type: 1, name: `Synthetic pending C ${randomBytes(5).toString('hex')}` });
    const account = await api('v1/accounts/add.json', seed.owner, { ledgerId: ledger.id, name: 'Synthetic ordinary recovery CNY',
        category: 2, type: 1, icon: '1', color: '000000', currency: 'CNY', balance: 10000, balanceTime: 946684800 });
    const current = page => page.locator('.view-main > .page.page-current');
    const pendingAction = (page, form, name) => page.pendingPlatform === 'desktop'
        ? form.getByRole('button', { name, exact: true }) : form.getByText(name, { exact: true });
    const endpoint = '**/api/v1/transactions/add.json';
    const cases = [], pendingCounts = local => ({ ai: 0, periodic: 0, local });
    let expectedBalance = 10000, expectedExpenseCount = 0;

    async function expenseState() {
        const params = new URLSearchParams({ ledgerId: ledger.id, type: '3', max_time: '0', min_time: '0', page: '1', count: '50', with_count: 'true' });
        const page = await api(`v1/transactions/list.json?${params}`, seed.owner);
        assert(Array.isArray(page.items)); assert.equal(page.totalCount, expectedExpenseCount);
        assert.equal(page.items.length, expectedExpenseCount, 'all synthetic expenses fit in a single verified page');
        const balance = (await api(`v1/accounts/get.json?ledgerId=${ledger.id}&id=${account.id}`, seed.owner)).balance;
        assert.equal(balance, expectedBalance, 'each expense changes only C by its amount once');
        return { ...page, balance };
    }

    async function financialState(comment, amount, expectedId) {
        const page = await expenseState();
        const rows = page.items.filter(row => row.comment === comment);
        assert.equal(rows.length, 1, 'exactly one real stored expense for the original request');
        assert.equal(rows[0].sourceAmount, amount); assert.equal(rows[0].sourceAccountId, account.id);
        assert.equal(rows[0].ledgerId, ledger.id);
        if (expectedId) assert.equal(rows[0].id, expectedId);
        return { id: rows[0].id, balance: page.balance, expenseCount: page.totalCount };
    }

    async function editor(page) {
        const form = page.pendingPlatform === 'desktop'
            ? page.getByRole('dialog').filter({ has: page.locator('.transaction-edit-card') })
            : current(page);
        await form.waitFor(); return form;
    }

    async function openNew(page, comment, amount) {
        step(page, 'open a real ordinary expense in independent synthetic ledger C');
        if (page.pendingPlatform === 'desktop') {
            // Keep this ordinary recovery matrix on normal application
            // navigation. Cold full-document query links are investigated in
            // a separate diagnostic; they are not asserted by this helper.
            await page.evaluate(() => { window.location.hash = '/transaction/list?action=expense'; });
            const form = await editor(page);
            await form.getByLabel(/^支出金额/).fill((amount / 100).toFixed(2));
            await form.getByRole('combobox', { name: '分类', exact: true }).locator('..').click();
            const menu = page.locator('.two-column-select-menu').filter({ visible: true });
            await menu.getByText(seed.categoryRoot.name, { exact: true }).click();
            await menu.getByText(seed.category.name, { exact: true }).click();
            await form.locator('textarea').first().fill(comment);
            await form.getByRole('button', { name: '添加', exact: true }).and(page.locator(':enabled')).waitFor();
        } else {
            const params = new URLSearchParams({ ledgerId: ledger.id, type: '3', categoryId: seed.category.id,
                accountId: account.id, destinationAccountId: '0', amount: String(amount), comment, noTransactionDraft: 'true' });
            // Framework7 route queries decode percent escapes, matching the
            // application's encodeURIComponent link builder, rather than the
            // HTML form '+' convention used by URLSearchParams.toString().
            await mobileNavigate(page, `/transaction/add?${params.toString().replaceAll('+', '%20')}`);
            await current(page).locator('textarea').first().waitFor();
            await page.waitForFunction(() => !!document.querySelector('.page-current textarea') && !document.querySelector('.page-current .skeleton-text'));
            assert.equal(await current(page).locator('textarea').first().inputValue(), comment);
            await page.getByLabel('保存流水', { exact: true }).last().waitFor();
        }
        return editor(page);
    }

    async function submit(page, form) {
        if (page.pendingPlatform === 'desktop') await form.getByRole('button', { name: '添加', exact: true }).click();
        else await page.getByLabel('保存流水', { exact: true }).last().click();
    }

    async function originalFromNotification(page, state, actionName, comment) {
        // A document reload discards the native fault injection and all in-memory
        // state. Only the original production-written journal can restore this.
        if (page.pendingPlatform === 'desktop') {
            const originalEditor = await editor(page);
            await originalEditor.getByRole('button', { name: '取消', exact: true }).click();
            await originalEditor.waitFor({ state: 'hidden' });
        }
        await home(page); await page.reload({ waitUntil: 'networkidle' });
        await home(page); await refresh(page); await counts(homePanel(page), pendingCounts(1));
        const before = readOnlyStart(page);
        let panel;
        if (page.pendingPlatform === 'desktop') panel = (await notifications(page, pendingCounts(1))).getByTestId('ledger-pending');
        else {
            panel = homePanel(page);
            const group = panel.getByTestId('pending-local');
            if (await group.getAttribute('open') === null) await group.locator('summary').click();
        }
        const item = panel.getByTestId('pending-local').locator(`li[data-pending-state="${state}"]`);
        assert.equal(await item.count(), 1, 'pending plus its editor draft must contribute one local entry');
        await item.getByRole('button', { name: '恢复原记账', exact: true }).click();
        const form = await editor(page);
        await pendingAction(page, form, actionName).waitFor();
        assert.equal(await form.locator('textarea').first().inputValue(), comment, 'notification preserves original content');
        assert.equal(await form.locator('textarea').first().evaluate(element => !!element.closest('[inert]')), true, 'original pending form stays locked');
        readOnlyEnd(page, before, `${page.pendingPlatform} ${page.pendingPlatform === 'desktop' ? 'notification' : 'home pending entry'} opens ${state} original ordinary request without writing`);
        return form;
    }

    async function completed(page, form) {
        if (page.pendingPlatform === 'desktop') await form.waitFor({ state: 'hidden' });
        else await page.waitForFunction(() => !document.querySelector('.page-current.mobile-transaction-edit-page'));
        await home(page); await refresh(page); await counts(homePanel(page), pendingCounts(0));
    }

    async function delayedMembership(page) {
        let release, entered, finished, held = false, failure, delivered = false;
        const released = new Promise(resolve_ => { release = resolve_; });
        const received = new Promise(resolve_ => { entered = resolve_; });
        const done = new Promise(resolve_ => { finished = resolve_; });
        const pattern = '**/api/v1/ledger/member/list.json*';
        await page.route(pattern, async route => {
            if (held || new URL(route.request().url()).searchParams.get('ledgerId') !== ledger.id) { await route.continue(); return; }
            held = true;
            try {
                const response = await route.fetch(); assert.equal(response.status(), 200);
                await response.body(); entered(); await released;
                // Delay the unmodified real response; no server JSON is mocked.
                await route.fulfill({ response }); delivered = true;
            } catch (error) { failure = error; entered(); }
            finally { finished(); }
        });
        return {
            async received() { await Promise.race([received, new Promise((_, reject) => setTimeout(() => reject(new Error('ordinary save did not reach its live membership request')), 20000))]); if (failure) throw failure; },
            async release() { release(); await done; await page.unroute(pattern); if (failure) throw failure; assert(delivered); }
        };
    }

    async function readyPendingAction(page, form, name) {
        const action = pendingAction(page, form, name);
        await action.and(page.locator(':not(.disabled):not(:disabled)')).waitFor();
        return action;
    }

    for (const platform of ['desktop', 'mobile']) {
        const page = await login(seed.owner, platform); await changeLedger(page, ledger);
        await counts(homePanel(page), pendingCounts(0));
        const posts = [], reads = [];
        page.on('request', request => {
            const url = new URL(request.url());
            if (url.pathname === '/api/v1/transactions/add.json') posts.push(request.postDataJSON());
            if (url.pathname === '/api/v1/transactions/get.json') reads.push({ method: request.method(), id: url.searchParams.get('id'), ledgerId: url.searchParams.get('ledgerId') });
        });

        // Unknown result: route.fetch reaches the actual server and commits.
        // The caller receives a network failure instead of a fabricated body.
        const lostAmount = platform === 'desktop' ? 77 : 93;
        const lostComment = `Synthetic ${platform} ordinary committed response lost`;
        let form = await openNew(page, lostComment, lostAmount), committed, dropped = 0, routeFailure;
        await page.route(endpoint, async route => {
            try {
                const response = await route.fetch(); assert.equal(response.status(), 200);
                const body = await response.json(); assert.equal(body.success, true); committed = body.result;
                dropped++; await route.abort('failed');
            } catch (error) { routeFailure = error; await route.abort('failed').catch(() => {}); }
        });
        const postIndex = posts.length;
        try {
            step(page, 'drop only the real committed ordinary response');
            await submit(page, form); await pendingAction(page, form, '重试原请求').waitFor();
        } finally { await page.unroute(endpoint); }
        if (routeFailure) throw routeFailure;
        assert.equal(dropped, 1); assert(committed); assert.equal(posts.length, postIndex + 1);
        const original = posts[postIndex];
        assert.equal(typeof original.clientSessionId, 'string'); assert(original.clientSessionId);
        assert.equal(original.ledgerId, ledger.id); assert.equal(original.sourceAmount, lostAmount);
        assert.equal(original.sourceAccountId, account.id); assert.equal(original.comment, lostComment);
        expectedBalance -= lostAmount; expectedExpenseCount++;
        const lostCommitted = await financialState(lostComment, lostAmount, committed.id);
        await page.screenshot({ path: resolve(output, `ordinary-${platform}-unknown-result.png`), fullPage: true });
        form = await originalFromNotification(page, 'unknown-result', '重试原请求', lostComment);
        assert.equal(posts.length, postIndex + 1, 'navigation and document restart cannot replay the unknown POST');
        const retry = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/transactions/add.json' && response.request().method() === 'POST');
        step(page, 'explicitly retry the same original ordinary request');
        await pendingAction(page, form, '重试原请求').click();
        const retryResponse = await retry; assert.equal(retryResponse.status(), 200);
        assert.deepEqual(retryResponse.request().postDataJSON(), original, 'all original request fields and the idempotency key are unchanged');
        assert.equal((await retryResponse.json()).result.id, committed.id);
        await completed(page, form); assert.equal(posts.length, postIndex + 2);
        assert.deepEqual(posts[postIndex + 1], original);
        assert.deepEqual(await financialState(lostComment, lostAmount, committed.id), lostCommitted);
        cases.push({ platform, scenario: 'unknown-result', transactionId: committed.id, clientSessionId: original.clientSessionId,
            amount: lostAmount, realPostAttempts: 2, committedRows: 1, ...lostCommitted });
        pass(`${platform} real ordinary lost-response recovery opens read-only from ${platform === 'desktop' ? 'notification' : 'home pending entry'} and explicitly replays the identical payload/key into one row`);

        // Known receipt: the first two locks use native IndexedDB normally.
        // The third open is the acknowledgement lock, after POST and receipt
        // persistence. This is native API fault injection, not journal setup.
        const receiptAmount = platform === 'desktop' ? 81 : 105;
        const receiptComment = `Synthetic ${platform} ordinary known receipt ack fault`;
        form = await openNew(page, receiptComment, receiptAmount);
        await page.evaluate(() => {
            const factory = indexedDB, nativeOpen = factory.open;
            const fault = { kind: 'one-shot native IndexedDB.open fault injection', database: 'finexy-transaction-operations',
                calls: 0, nativeCalls: 0, failures: 0, failAt: 3 };
            window.__finexyOrdinaryNativeFault = fault;
            Object.defineProperty(factory, 'open', { configurable: true, value(name, ...arguments_) {
                if (name === fault.database) {
                    fault.calls++;
                    if (fault.calls === fault.failAt) {
                        fault.failures++;
                        throw new DOMException('Synthetic one-shot acknowledgement storage fault', 'UnknownError');
                    }
                    fault.nativeCalls++;
                }
                return nativeOpen.call(factory, name, ...arguments_);
            } });
        });
        const receiptPostIndex = posts.length;
        const written = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/transactions/add.json' && response.request().method() === 'POST');
        step(page, 'commit a real expense and inject one native acknowledgement-lock failure');
        await submit(page, form); const writtenResponse = await written; assert.equal(writtenResponse.status(), 200);
        const receiptRow = (await writtenResponse.json()).result;
        await pendingAction(page, form, '重新读取原结果').waitFor();
        const fault = await page.evaluate(() => window.__finexyOrdinaryNativeFault);
        assert.equal(fault.calls, 3); assert.equal(fault.nativeCalls, 2); assert.equal(fault.failures, 1);
        assert.equal(posts.length, receiptPostIndex + 1);
        expectedBalance -= receiptAmount; expectedExpenseCount++;
        const receiptCommitted = await financialState(receiptComment, receiptAmount, receiptRow.id);
        await page.screenshot({ path: resolve(output, `ordinary-${platform}-known-receipt.png`), fullPage: true });
        form = await originalFromNotification(page, 'receipt', '重新读取原结果', receiptComment);
        assert.equal(await page.evaluate(() => Object.hasOwn(window, '__finexyOrdinaryNativeFault')), false, 'document reload removes the one-shot native fault patch');
        assert.equal(posts.length, receiptPostIndex + 1, 'receipt restoration must not create again');
        const readIndex = reads.length, beforeRead = readOnlyStart(page);
        step(page, 'explicitly reread the known original transaction and acknowledge locally');
        await pendingAction(page, form, '重新读取原结果').click();
        await completed(page, form);
        readOnlyEnd(page, beforeRead, `${platform} known ordinary receipt recovery performs GET and local acknowledgement only`);
        assert.equal(posts.length, receiptPostIndex + 1, 'known receipt recovery never repeats a POST');
        assert(reads.slice(readIndex).some(read => read.method === 'GET' && read.id === receiptRow.id && read.ledgerId === ledger.id), 'read the exact original transaction ID in original ledger C');
        assert.deepEqual(await financialState(receiptComment, receiptAmount, receiptRow.id), receiptCommitted);
        cases.push({ platform, scenario: 'known-receipt', transactionId: receiptRow.id, clientSessionId: posts[receiptPostIndex].clientSessionId,
            amount: receiptAmount, realPostAttempts: 1, committedRows: 1, nativeApiFaultInjection: fault, ...receiptCommitted });
        pass(`${platform} real ordinary known receipt survives one native IndexedDB acknowledgement fault and ${platform === 'desktop' ? 'notification' : 'home pending entry'} recovery only GETs the original row`);

        // Leave while Save is awaiting the real current membership. The late
        // response must not turn the departed form into a background write.
        const departedComment = `Synthetic ${platform} departed ordinary save must never post`;
        form = await openNew(page, departedComment, 119);
        let permission = await delayedMembership(page);
        const departedPostIndex = posts.length;
        step(page, 'leave the ordinary editor while its live permission response is delayed');
        await submit(page, form); await permission.received();
        if (platform === 'desktop') {
            await page.goBack({ waitUntil: 'domcontentloaded' });
            await form.waitFor({ state: 'hidden' });
        } else await mobileNavigate(page, '/');
        await permission.release();
        await home(page); await refresh(page); await counts(homePanel(page), pendingCounts(0));
        assert.equal(posts.length, departedPostIndex, 'departing during membership cannot start an ordinary POST');
        assert.equal((await expenseState()).items.some(row => row.comment === departedComment), false);
        cases.push({ platform, scenario: 'departed-during-live-permission', realPostAttempts: 0, committedRows: 0,
            permissionResponse: 'real HTTP 200 body delayed and fulfilled without modification' });
        pass(`${platform} ordinary Save cannot POST after leaving during a delayed real permission response`);

        // While a fresh Save waits for its membership response, another real
        // page commits a request but loses its reply. The first Save must only
        // display that original pending request, never silently replay it.
        const waitingComment = `Synthetic ${platform} waiting fresh save must not replace pending`;
        form = await openNew(page, waitingComment, 131);
        permission = await delayedMembership(page);
        const waitingPostIndex = posts.length;
        await submit(page, form); await permission.received();
        const other = await page.context().newPage(), otherPosts = [], otherErrors = [], otherConsoleErrors = [];
        other.setDefaultTimeout(20000);
        other.pendingPlatform = platform; other.apiRequests = []; other.pendingTargets = []; other.createPosts = [];
        other.on('pageerror', error => otherErrors.push(error.message));
        other.on('console', message => {
            if (message.type() === 'error') otherConsoleErrors.push({ message: message.text(), location: message.location().url.split('?')[0] });
        });
        other.on('request', request => {
            const url = new URL(request.url());
            if (url.pathname.startsWith('/api/')) other.apiRequests.push({ method: request.method(), path: url.pathname, ledgerId: url.searchParams.get('ledgerId') });
            if (url.pathname === '/api/v1/transactions/add.json') otherPosts.push(request.postDataJSON());
        });
        await other.goto(`${base}/${platform}${platform === 'desktop' ? '#/' : ''}`, { waitUntil: 'networkidle' });
        await counts(homePanel(other), pendingCounts(0));
        const competingAmount = platform === 'desktop' ? 137 : 149;
        const competingComment = `Synthetic ${platform} competing original pending request`;
        const otherForm = await openNew(other, competingComment, competingAmount);
        let competingRow, otherRouteFailure;
        await other.route(endpoint, async route => {
            try {
                const response = await route.fetch(); assert.equal(response.status(), 200);
                const body = await response.json(); assert.equal(body.success, true); competingRow = body.result;
                await route.abort('failed');
            } catch (error) { otherRouteFailure = error; await route.abort('failed').catch(() => {}); }
        });
        try {
            await submit(other, otherForm); await pendingAction(other, otherForm, '重试原请求').waitFor();
        } finally { await other.unroute(endpoint); }
        if (otherRouteFailure) throw otherRouteFailure;
        assert(competingRow); assert.equal(otherPosts.length, 1);
        const competingRequest = otherPosts[0];
        expectedBalance -= competingAmount; expectedExpenseCount++;
        const competingCommitted = await financialState(competingComment, competingAmount, competingRow.id);
        await permission.release(); await page.bringToFront();
        await readyPendingAction(page, form, '重试原请求');
        assert.equal(await form.locator('textarea').first().inputValue(), competingComment);
        assert.equal(posts.length, waitingPostIndex, 'fresh Save cannot adopt and automatically retry another tab original pending');
        assert.equal((await expenseState()).items.some(row => row.comment === waitingComment), false);
        const explicitRetry = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/transactions/add.json' && response.request().method() === 'POST');
        await pendingAction(page, form, '重试原请求').click();
        const explicitResponse = await explicitRetry; assert.equal(explicitResponse.status(), 200);
        assert.deepEqual(explicitResponse.request().postDataJSON(), competingRequest);
        assert.equal((await explicitResponse.json()).result.id, competingRow.id);
        await completed(page, form); assert.equal(posts.length, waitingPostIndex + 1);
        assert.deepEqual(await financialState(competingComment, competingAmount, competingRow.id), competingCommitted);
        assert.deepEqual(otherErrors, []);
        assert.deepEqual(otherConsoleErrors.filter(item => !(
            (item.location === `${base}/api/v1/transactions/add.json` && /^Failed to load resource: net::ERR_FAILED/.test(item.message)) ||
            (item.location === 'https://api.github.com/repos/SMTM-PH/finexy-ai-bookkeeping/releases/latest' && /^Failed to load resource: the server responded with a status of 403\b/.test(item.message))
        )), [], 'the extra real page must not hide unexpected console errors');
        await other.close();
        cases.push({ platform, scenario: 'other-tab-pending-during-live-permission', transactionId: competingRow.id,
            clientSessionId: competingRequest.clientSessionId, amount: competingAmount, freshSavePosts: 0,
            otherPageCommittedPosts: 1, explicitOriginalRetries: 1, committedRows: 1, expectedOtherPageConsoleErrors: otherConsoleErrors, ...competingCommitted });
        pass(`${platform} a fresh ordinary Save waiting for real permission only displays another tab original pending and requires a separate explicit identical-key retry`);
    }

    for (const original of [seed.personal, seed.ledgerA, seed.ledgerB]) {
        assert.equal((await api(`v1/accounts/get.json?ledgerId=${original.id}&id=${seed.accounts.get(original.id).id}`, seed.owner)).balance,
            seed.balances.get(original.id), 'independent C acceptance preserves original A/B/default balances');
    }
    const result = { status: 'passed', ledgerId: ledger.id, accountId: account.id, expenseRows: expectedExpenseCount,
        startingBalance: 10000, finalBalance: expectedBalance, cases,
        nativeApiFaultInjection: 'Each platform injects one IndexedDB.open DOMException at lock 3; locks 1 and 2 use native storage; reload removes the patch.',
        serverResponsesMocked: false, journalsInjected: false, piniaInjected: false, legacyKeylessDraft: 'unit coverage only; no synthetic browser journal injection' };
    await writeFile(resolve(output, 'ordinary-recovery-summary.json'), JSON.stringify(result, null, 2));
    return result;
}
