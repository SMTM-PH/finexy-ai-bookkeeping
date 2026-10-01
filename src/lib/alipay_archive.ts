import { BlobReader, BlobWriter, ZipReader } from '@zip.js/zip.js';

const MAX_CSV_BYTES = 20 * 1024 * 1024;

// The archive is opened locally so the ZIP password never reaches the server.
export async function extractAlipayCsv(archive: File, password: string): Promise<File> {
    const reader = new ZipReader(new BlobReader(archive));

    try {
        const entries = (await reader.getEntries()).filter(entry => !entry.directory && !entry.filename.startsWith('__MACOSX/'));
        const csvEntries = entries.filter(entry => /(^|\/)支付宝交易明细[^/]*\.csv$/i.test(entry.filename));

        if (entries.length !== 1 || csvEntries.length !== 1) {
            throw new Error('压缩包应只包含一份支付宝交易明细 CSV 文件');
        }

        const entry = csvEntries[0]!;
        if (entry.directory) {
            throw new Error('压缩包中没有 CSV 文件');
        }

        if (entry.uncompressedSize > MAX_CSV_BYTES) {
            throw new Error('支付宝 CSV 文件超过 20 MB 上限');
        }

        let blob: Blob;

        try {
            blob = await entry.getData(new BlobWriter(), { password, checkSignature: true });
        } catch {
            throw new Error('解压失败，请检查压缩包密码或文件是否损坏');
        }

        if (blob.size > MAX_CSV_BYTES) {
            throw new Error('支付宝 CSV 文件超过 20 MB 上限');
        }

        const name = entry.filename.split('/').pop()!;
        return new File([blob], name, { type: 'text/csv' });
    } finally {
        await reader.close();
    }
}
