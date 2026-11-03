export interface AccountCardStyle {
    revision: number;
    theme: 'classic' | 'midnight' | 'mint' | 'copper';
    background: string;
    subject: string;
    imageX: number;
    imageY: number;
    imageZoom: number;
    subjectX: number;
    subjectY: number;
    subjectZoom: number;
    foil: number;
    depth: number;
}

export function defaultAccountCardStyle(): AccountCardStyle {
    return { revision: 0, theme: 'classic', background: '', subject: '', imageX: 50, imageY: 50, imageZoom: 100, subjectX: 50, subjectY: 50, subjectZoom: 100, foil: 22, depth: 50 };
}

export function parseAccountCardStyle(value: unknown): AccountCardStyle {
    if (!value || typeof value !== 'object') throw new Error('Invalid card appearance');
    const v = value as AccountCardStyle;
    if (!Number.isSafeInteger(v.revision) || v.revision < 0 || !['classic', 'midnight', 'mint', 'copper'].includes(v.theme)) throw new Error('Invalid card appearance');
    for (const key of ['imageX', 'imageY', 'subjectX', 'subjectY', 'foil', 'depth'] as const) {
        if (!Number.isInteger(v[key]) || v[key] < 0 || v[key] > 100) throw new Error('Invalid card appearance');
    }
    if (!Number.isInteger(v.imageZoom) || v.imageZoom < 100 || v.imageZoom > 200 || !Number.isInteger(v.subjectZoom) || v.subjectZoom < 25 || v.subjectZoom > 200) throw new Error('Invalid card appearance');
    for (const image of [v.background, v.subject]) {
        if (typeof image !== 'string' || image.length > 700000 || (image && !/^data:image\/(png|jpeg);base64,[A-Za-z0-9+/]+={0,2}$/.test(image))) throw new Error('Invalid card image');
    }
    // Rebuild known fields only; a response cannot inject arbitrary CSS or extra settings.
    return { revision: v.revision, theme: v.theme, background: v.background, subject: v.subject, imageX: v.imageX, imageY: v.imageY, imageZoom: v.imageZoom, subjectX: v.subjectX, subjectY: v.subjectY, subjectZoom: v.subjectZoom, foil: v.foil, depth: v.depth };
}

// Re-encode in the browser to bound storage and remove original image metadata.
export async function prepareAccountCardImage(file: File, transparent: boolean): Promise<string> {
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size > 10 * 1024 * 1024) throw new Error('请选择 10 MB 以内的 JPG、PNG 或 WebP 图片。');
    const url = URL.createObjectURL(file);
    try {
        const img = new Image();
        img.src = url;
        await img.decode();
        if (!img.naturalWidth || !img.naturalHeight || img.naturalWidth * img.naturalHeight > 40000000) throw new Error('图片尺寸过大，请选择较小的图片。');
        for (const max of [1200, 900, 600, 400]) {
            const scale = Math.min(1, max / Math.max(img.naturalWidth, img.naturalHeight));
            const canvas = document.createElement('canvas');
            canvas.width = Math.max(1, Math.round(img.naturalWidth * scale));
            canvas.height = Math.max(1, Math.round(img.naturalHeight * scale));
            const context = canvas.getContext('2d');
            if (!context) throw new Error('浏览器无法处理图片，请更换浏览器重试。');
            context.drawImage(img, 0, 0, canvas.width, canvas.height);
            const data = canvas.toDataURL(transparent ? 'image/png' : 'image/jpeg', 0.85);
            if (data.length < 690000) return data;
        }
        throw new Error('图片压缩后仍过大，请选择较简单的图片。');
    } catch (error) {
        if (error instanceof Error && !(error instanceof DOMException)) throw error;
        throw new Error('无法读取图片，请选择有效的 JPG、PNG 或 WebP。');
    } finally { URL.revokeObjectURL(url); }
}
