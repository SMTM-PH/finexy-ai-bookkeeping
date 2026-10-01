import { describe, expect, it } from 'vitest';
import { BlobWriter, TextReader, ZipWriter } from '@zip.js/zip.js';
import { extractAlipayCsv } from '../alipay_archive.ts';

async function archive(entries: Array<[string, string]>, password = 'secret'): Promise<File> {
    const writer = new ZipWriter(new BlobWriter());
    for (const [name, contents] of entries) {
        await writer.add(name, new TextReader(contents), { password });
    }
    return new File([await writer.close()], '支付宝.zip', { type: 'application/zip' });
}

describe('Alipay ZIP import', () => {
    it('extracts exactly one encrypted CSV with the supplied password', async () => {
        const input = await archive([['支付宝交易明细(2026).csv', '交易时间,金额\n2026-01-01,1.00']]);
        const output = await extractAlipayCsv(input, 'secret');
        expect(output.name).toBe('支付宝交易明细(2026).csv');
        expect(await output.text()).toContain('交易时间,金额');
    });

    it('rejects an incorrect password and ambiguous archives', async () => {
        const input = await archive([['支付宝交易明细(2026).csv', 'test']]);
        await expect(extractAlipayCsv(input, 'wrong')).rejects.toThrow('解压失败');
        const ambiguous = await archive([['支付宝交易明细1.csv', 'a'], ['支付宝交易明细2.csv', 'b']]);
        await expect(extractAlipayCsv(ambiguous, 'secret')).rejects.toThrow('一份');
    });
});
