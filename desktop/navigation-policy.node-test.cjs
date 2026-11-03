const { test } = require('node:test');
const assert = require('node:assert/strict');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const { isTrustedServerUrl, isTrustedPageUrl, isTrustedIpcEvent, isSafeExternalUrl } = require('./navigation-policy.cjs');

const server = 'http://127.0.0.1:8080';
const offline = pathToFileURL(path.join(__dirname, 'offline.html')).href;

test('server navigation compares protocol, host and port and rejects URL credentials', () => {
    assert(isTrustedServerUrl(server + '/desktop#/transaction/list', server));
    assert(isTrustedServerUrl('https://example.invalid:443/desktop', 'https://example.invalid'));
    for (const target of [server + '@evil.invalid/', server + '.evil.invalid/', 'http://127.0.0.1:8081/',
        'https://127.0.0.1:8080/', 'http://name:secret@127.0.0.1:8080/', 'javascript:alert(1)', 'not a URL']) {
        assert.equal(isTrustedServerUrl(target, server), false, target);
    }
});

test('only the packaged offline page is trusted as a file', () => {
    assert(isTrustedPageUrl(offline, server, offline));
    assert(isTrustedPageUrl(server + '/desktop', server, offline));
    assert.equal(isTrustedPageUrl(pathToFileURL(path.join(__dirname, 'preload.cjs')).href, server, offline), false);
    assert.equal(isTrustedPageUrl(offline + '?injected=1', server, offline), false);
    assert.equal(isTrustedPageUrl('file:///etc/passwd', server, offline), false);
});

test('desktop IPC requires this window, its main frame and a trusted current URL', () => {
    const mainFrame = { url: server + '/desktop' };
    const contents = { mainFrame };
    const event = { sender: contents, senderFrame: mainFrame };
    assert(isTrustedIpcEvent(event, contents, server, offline));
    mainFrame.url = offline;
    assert(isTrustedIpcEvent(event, contents, server, offline));
    mainFrame.url = server + '@evil.invalid/';
    assert.equal(isTrustedIpcEvent(event, contents, server, offline), false);
    mainFrame.url = server;
    assert.equal(isTrustedIpcEvent({ sender: {}, senderFrame: mainFrame }, contents, server, offline), false);
    assert.equal(isTrustedIpcEvent({ sender: contents, senderFrame: { url: server } }, contents, server, offline), false);
    assert.equal(isTrustedIpcEvent({ sender: contents, senderFrame: null }, contents, server, offline), false);
});

test('external links allow only HTTP(S) without embedded credentials', () => {
    assert(isSafeExternalUrl('https://example.invalid/releases'));
    for (const target of ['file:///tmp/run.exe', 'javascript:alert(1)', 'data:text/html,test', 'mailto:test@example.invalid', server + '@evil.invalid/']) {
        assert.equal(isSafeExternalUrl(target), false, target);
    }
});
