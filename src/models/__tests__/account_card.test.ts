import { describe, expect, it } from 'vitest';
import { defaultAccountCardStyle, parseAccountCardStyle } from '../account_card.ts';

describe('account card response validation', () => {
    it('rejects external URLs, executable images, invalid controls and incomplete responses', () => {
        const good = defaultAccountCardStyle();
        expect(parseAccountCardStyle(good)).toEqual(good);
        for (const invalid of [{ ...good, background: 'https://example.invalid/image.png' }, { ...good, subject: 'data:image/svg+xml;base64,PHN2Zz4=' }, { ...good, imageX: NaN }, { ...good, imageZoom: 201 }, { ...good, revision: -1 }, { ...good, theme: 'url(...)' }, {}]) {
            expect(() => parseAccountCardStyle(invalid)).toThrow();
        }
    });
});
