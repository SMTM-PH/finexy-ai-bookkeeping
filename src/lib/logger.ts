import { isEnableDebug } from './settings.ts';

function logDebug(msg: string, obj?: unknown): void {
    if (isEnableDebug()) {
        if (obj) {
            console.debug('[Finexy Debug] ' + msg, obj);
        } else {
            console.debug('[Finexy Debug] ' + msg);
        }
    }
}

function logInfo(msg: string, obj?: unknown): void {
    if (obj) {
        console.info('[Finexy Info] ' + msg, obj);
    } else {
        console.info('[Finexy Info] ' + msg);
    }
}

function logWarn(msg: string, obj?: unknown): void {
    if (obj) {
        console.warn('[Finexy Warn] ' + msg, obj);
    } else {
        console.warn('[Finexy Warn] ' + msg);
    }
}

function logError(msg: string, obj?: unknown): void {
    if (obj) {
        console.error('[Finexy Error] ' + msg, obj);
    } else {
        console.error('[Finexy Error] ' + msg);
    }
}

export default {
    debug: logDebug,
    info: logInfo,
    warn: logWarn,
    error: logError
};
