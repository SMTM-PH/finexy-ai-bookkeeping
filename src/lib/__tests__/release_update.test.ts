import { beforeEach, describe, expect, it, vi } from 'vitest';

const { getServerVersion } = vi.hoisted(() => ({ getServerVersion: vi.fn() }));
vi.mock('@/lib/services.ts', () => ({ default: { getServerVersion } }));
vi.mock('@/lib/version.ts', () => ({ getClientVersionInfo: () => ({ version: '1.9.1' }) }));

import {
    availableRelease,
    checkForWebUpdate,
    compareStableVersions,
    dismissWebUpdate,
    parseStableRelease
} from '@/lib/release_update.ts';

const release = (version: string) => ({
    tag_name: `v${version}`,
    draft: false,
    prerelease: false,
    html_url: `https://github.com/SMTM-PH/finexy-ai-bookkeeping/releases/tag/v${version}`
});

describe('Web release reminder', () => {
    const storage = new Map<string, string>();

    beforeEach(() => {
        storage.clear();
        availableRelease.value = null;
        getServerVersion.mockReset().mockResolvedValue({ data: { result: { version: '1.9.1' } } });
        vi.stubGlobal('window', { setTimeout, clearTimeout });
        vi.stubGlobal('localStorage', {
            getItem: (key: string) => storage.get(key) ?? null,
            setItem: (key: string, value: string) => { storage.set(key, value); }
        });
        vi.stubGlobal('fetch', vi.fn());
    });

    it('orders numeric versions and rejects malformed or pre-release tags', () => {
        expect(compareStableVersions('v1.10.0', '1.9.9')).toBe(1);
        expect(compareStableVersions('1.9.1', 'v1.9.1')).toBe(0);
        expect(compareStableVersions('1.9.1-beta', '1.9.0')).toBeNull();
        expect(parseStableRelease({ ...release('1.10.0'), prerelease: true })).toBeNull();
        expect(parseStableRelease({ ...release('1.10.0'), html_url: 'https://example.com/' })).toBeNull();
    });

    it('shows a newer release, remembers dismissal, and shows a later release', async () => {
        vi.mocked(fetch).mockResolvedValueOnce({ ok: true, json: async () => release('1.10.0') } as Response);
        expect((await checkForWebUpdate()).status).toBe('available');
        expect(availableRelease.value?.version).toBe('1.10.0');
        dismissWebUpdate();
        expect(availableRelease.value).toBeNull();
        await checkForWebUpdate(); // Uses the 24-hour cache.
        expect(availableRelease.value).toBeNull();
        vi.mocked(fetch).mockResolvedValueOnce({ ok: true, json: async () => release('1.11.0') } as Response);
        await checkForWebUpdate(true);
        expect(availableRelease.value?.version).toBe('1.11.0');
        expect(fetch).toHaveBeenCalledTimes(2);
    });

    it('compares against the deployed server and reports an up-to-date version', async () => {
        getServerVersion.mockResolvedValue({ data: { result: { version: '1.12.0' } } });
        vi.mocked(fetch).mockResolvedValue({ ok: true, json: async () => release('1.11.0') } as Response);
        expect(await checkForWebUpdate()).toEqual({ status: 'current', currentVersion: '1.12.0', latestVersion: '1.11.0' });
        expect(availableRelease.value).toBeNull();
    });

    it('uses the client version when the server version endpoint fails', async () => {
        getServerVersion.mockRejectedValue(new Error('offline'));
        vi.mocked(fetch).mockResolvedValue({ ok: true, json: async () => release('1.10.0') } as Response);
        await checkForWebUpdate();
        expect(availableRelease.value?.currentSource).toBe('client');
    });

    it('does not invent an update when the release request fails', async () => {
        vi.mocked(fetch).mockRejectedValue(new Error('offline'));
        await expect(checkForWebUpdate()).rejects.toThrow('offline');
        expect(availableRelease.value).toBeNull();
    });
});
