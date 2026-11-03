const { URL } = require('node:url');

function parseHttpUrl(value) {
    try {
        const parsed = new URL(value);
        return ['http:', 'https:'].includes(parsed.protocol) && !parsed.username && !parsed.password ? parsed : null;
    } catch {
        return null;
    }
}

function isTrustedServerUrl(value, serverUrl) {
    const target = parseHttpUrl(value);
    const server = parseHttpUrl(serverUrl);
    return !!target && !!server && target.origin === server.origin;
}

function isTrustedPageUrl(value, serverUrl, offlineUrl) {
    if (isTrustedServerUrl(value, serverUrl)) return true;
    try {
        const target = new URL(value);
        return target.protocol === 'file:' && target.href === new URL(offlineUrl).href;
    } catch {
        return false;
    }
}

function isTrustedIpcEvent(event, webContents, serverUrl, offlineUrl) {
    return !!webContents && event.sender === webContents && !!event.senderFrame &&
        event.senderFrame === webContents.mainFrame &&
        isTrustedPageUrl(event.senderFrame.url, serverUrl, offlineUrl);
}

module.exports = { isTrustedServerUrl, isTrustedPageUrl, isTrustedIpcEvent, isSafeExternalUrl: value => !!parseHttpUrl(value) };
