import CryptoJS from 'crypto-js';
import { getTransactionStateIdentity } from './userstate.ts';
import { assertAIReviewSourceImage, type AIReviewSourceImage } from '@/models/ai_review_source.ts';
import { generateRandomUUID } from './misc.ts';

/** Image bytes stay in IndexedDB as ciphertext. The random per-image key is
 * carried by the scoped draft, so application-lock rotation only rewrites the
 * existing encrypted metadata rather than leaving an old PIN in a blob store. */
async function imageDatabase(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
        let abandoned = false;
        const opening = indexedDB.open('finexy-ai-source-images', 1);
        opening.onupgradeneeded = () => opening.result.createObjectStore('images');
        opening.onerror = () => reject(new Error('无法打开本机图片存储，请检查浏览器存储权限'));
        opening.onblocked = () => { abandoned = true; reject(new Error('图片存储正在升级，请关闭旧页面后重试')); };
        opening.onsuccess = () => { if (abandoned) opening.result.close(); else resolve(opening.result); };
    });
}
async function imageOperation(mode: IDBTransactionMode, operation: (store: IDBObjectStore) => IDBRequest): Promise<unknown> {
    const database = await imageDatabase();
    return new Promise((resolve, reject) => {
        const transaction = database.transaction('images', mode);
        let result: unknown;
        transaction.oncomplete = () => { database.close(); resolve(result); };
        transaction.onabort = transaction.onerror = () => { database.close(); reject(new Error('未能可靠保存或读取原图片，请保留输入后重试')); };
        try {
            const request = operation(transaction.objectStore('images'));
            request.onsuccess = () => { result = request.result; };
        } catch {
            transaction.abort(); database.close(); reject(new Error('未能可靠保存或读取原图片，请保留输入后重试'));
        }
    });
}
export async function saveAIReviewSourceImage(ledgerId: string, file: File): Promise<AIReviewSourceImage> {
    const identity = getTransactionStateIdentity();
    if (!identity) throw new Error('请登录后保存原图片');
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || !file.size || file.size > 20 * 1024 * 1024) throw new Error('请选择不超过 20 MB 的 JPEG、PNG 或 WebP 图片');
    const bytes = await file.arrayBuffer();
    const encoded = CryptoJS.enc.Base64.stringify(CryptoJS.lib.WordArray.create(bytes));
    const image: AIReviewSourceImage = { blobId: `${identity}ai-source-image:${ledgerId}:${generateRandomUUID()}`, secret: CryptoJS.lib.WordArray.random(32).toString(),
        sha256: CryptoJS.SHA256(encoded).toString(), mime: file.type, name: file.name, bytes: file.size };
    assertAIReviewSourceImage(image, ledgerId, identity);
    if (identity !== getTransactionStateIdentity()) throw new Error('账号已变化，请使用原账号恢复');
    await imageOperation('readwrite', store => store.put({ version: 1, scope: image.blobId, data: CryptoJS.AES.encrypt(encoded, image.secret).toString() }, image.blobId));
    return image;
}
export async function readAIReviewSourceImage(ledgerId: string, image: AIReviewSourceImage): Promise<File> {
    const identity = getTransactionStateIdentity();
    if (!identity) throw new Error('请先登录或解锁，再读取原图片');
    assertAIReviewSourceImage(image, ledgerId, identity);
    const record = await imageOperation('readonly', store => store.get(image.blobId)) as { version: number; scope: string; data: string } | undefined;
    if (identity !== getTransactionStateIdentity()) throw new Error('账号已变化，原图片不会显示到另一个账号');
    try {
        if (!record || record.version !== 1 || record.scope !== image.blobId || typeof record.data !== 'string') throw new Error();
        const encoded = CryptoJS.AES.decrypt(record.data, image.secret).toString(CryptoJS.enc.Utf8);
        if (CryptoJS.SHA256(encoded).toString() !== image.sha256) throw new Error();
        const bytes = Uint8Array.from(atob(encoded), value => value.charCodeAt(0));
        if (bytes.length !== image.bytes) throw new Error();
        return new File([bytes], image.name, { type: image.mime });
    } catch { throw new Error('原图片缺失或损坏，请保留草稿并重新核对'); }
}
export async function removeAIReviewSourceImage(ledgerId: string, image: AIReviewSourceImage): Promise<void> {
    const identity = getTransactionStateIdentity(); if (!identity) throw new Error('请先登录');
    assertAIReviewSourceImage(image, ledgerId, identity);
    await imageOperation('readwrite', store => store.delete(image.blobId));
}
