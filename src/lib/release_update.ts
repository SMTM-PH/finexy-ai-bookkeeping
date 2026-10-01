import { ref } from 'vue';

import services from '@/lib/services.ts';
import { getClientVersionInfo } from '@/lib/version.ts';

const RELEASE_API = 'https://api.github.com/repos/SMTM-PH/finexy-ai-bookkeeping/releases/latest';
const RELEASE_PATH = 'https://github.com/SMTM-PH/finexy-ai-bookkeeping/releases/tag/';
const CACHE_KEY = 'finexy.web.latest-release.v1';
const DISMISSED_KEY = 'finexy.web.dismissed-release.v1';
const CACHE_DURATION_MS = 24 * 60 * 60 * 1000;

export interface StableRelease {
    version: string;
    url: string;
}

export interface AvailableRelease extends StableRelease {
    currentVersion: string;
    currentSource: 'server' | 'client';
}

export type ReleaseCheckResult = { status: 'available'; release: AvailableRelease } |
    { status: 'current'; currentVersion: string; latestVersion: string };

export const availableRelease = ref<AvailableRelease | null>(null);
let pendingCheck: Promise<ReleaseCheckResult> | null = null;

export function parseStableVersion(value: unknown): number[] | null {
    if (typeof value !== 'string' || !/^v?\d+\.\d+\.\d+$/.test(value)) return null;
    const parts = value.replace(/^v/, '').split('.').map(Number);
    return parts.every(Number.isSafeInteger) ? parts : null;
}

export function compareStableVersions(left: string, right: string): number | null {
    const a = parseStableVersion(left);
    const b = parseStableVersion(right);
    if (!a || !b) return null;
    for (let i = 0; i < 3; i++) {
        if (a[i]! > b[i]!) return 1;
        if (a[i]! < b[i]!) return -1;
    }
    return 0;
}

export function parseStableRelease(value: unknown): StableRelease | null {
    if (!value || typeof value !== 'object') return null;
    const item = value as Record<string, unknown>;
    if (item['draft'] !== false || item['prerelease'] !== false || !parseStableVersion(item['tag_name'])) return null;
    const version = (item['tag_name'] as string).replace(/^v/, '');
    const url = `${RELEASE_PATH}${encodeURIComponent(item['tag_name'] as string)}`;
    if (item['html_url'] !== url) return null;
    return { version, url };
}

function readStorage(key: string): string | null {
    try { return localStorage.getItem(key); } catch { return null; }
}

function writeStorage(key: string, value: string): void {
    try { localStorage.setItem(key, value); } catch { /* Private browsing may disable storage. */ }
}

function cachedRelease(): StableRelease | null {
    const raw = readStorage(CACHE_KEY);
    if (!raw) return null;
    try {
        const cache = JSON.parse(raw) as { checkedAt?: number; release?: unknown };
        if (typeof cache.checkedAt !== 'number' || Date.now() - cache.checkedAt > CACHE_DURATION_MS || cache.checkedAt > Date.now()) return null;
        return parseStableRelease(cache.release);
    } catch { return null; }
}

async function latestRelease(force: boolean): Promise<StableRelease> {
    if (!force) {
        const cached = cachedRelease();
        if (cached) return cached;
    }
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 8000);
    try {
        const response = await fetch(RELEASE_API, {
            headers: { Accept: 'application/vnd.github+json' },
            signal: controller.signal
        });
        if (!response.ok) throw new Error('Release request failed');
        const payload: unknown = await response.json();
        const release = parseStableRelease(payload);
        if (!release) throw new Error('Invalid stable release');
        writeStorage(CACHE_KEY, JSON.stringify({
            checkedAt: Date.now(),
            release: {
                tag_name: (payload as { tag_name: string }).tag_name,
                draft: false,
                prerelease: false,
                html_url: release.url
            }
        }));
        return release;
    } finally {
        window.clearTimeout(timeout);
    }
}

async function runCheck(force: boolean): Promise<ReleaseCheckResult> {
    let currentVersion = getClientVersionInfo().version;
    let currentSource: 'server' | 'client' = 'client';
    try {
        const response = await services.getServerVersion();
        const serverVersion = response.data.result?.version;
        if (parseStableVersion(serverVersion)) {
            currentVersion = serverVersion;
            currentSource = 'server';
        }
    } catch { /* The page version still permits a useful check. */ }

    if (!parseStableVersion(currentVersion)) throw new Error('Unknown installed version');
    const release = await latestRelease(force);
    if (compareStableVersions(release.version, currentVersion)! <= 0) {
        availableRelease.value = null;
        return { status: 'current', currentVersion, latestVersion: release.version };
    }

    const available: AvailableRelease = { ...release, currentVersion, currentSource };
    availableRelease.value = force || readStorage(DISMISSED_KEY) !== release.version ? available : null;
    return { status: 'available', release: available };
}

export function checkForWebUpdate(force = false): Promise<ReleaseCheckResult> {
    if (pendingCheck) return pendingCheck;
    pendingCheck = runCheck(force).finally(() => { pendingCheck = null; });
    return pendingCheck;
}

export function dismissWebUpdate(): void {
    if (!availableRelease.value) return;
    writeStorage(DISMISSED_KEY, availableRelease.value.version);
    availableRelease.value = null;
}
