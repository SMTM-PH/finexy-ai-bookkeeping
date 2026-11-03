import { assertReceiptCorners, type ReceiptCorners } from '@/models/ai_review_source.ts';

/** Unit destination rectangle -> normalized source quadrilateral. */
export function receiptProjection(corners: ReceiptCorners): number[] {
    assertReceiptCorners(corners);
    const matrix: number[][] = [];
    const destination = [[0, 0], [1, 0], [1, 1], [0, 1]];
    for (let i = 0; i < 4; i++) {
        const [u, v] = destination[i] as [number, number], { x, y } = corners[i]!;
        matrix.push([u, v, 1, 0, 0, 0, -x * u, -x * v, x]);
        matrix.push([0, 0, 0, u, v, 1, -y * u, -y * v, y]);
    }
    for (let column = 0; column < 8; column++) {
        let pivot = column;
        for (let row = column + 1; row < 8; row++) if (Math.abs(matrix[row]![column]!) > Math.abs(matrix[pivot]![column]!)) pivot = row;
        [matrix[column], matrix[pivot]] = [matrix[pivot]!, matrix[column]!];
        const scale = matrix[column]![column]!;
        if (Math.abs(scale) < 1e-10) throw new Error('裁剪区域过小，请调整四角');
        for (let i = column; i <= 8; i++) matrix[column]![i]! /= scale;
        for (let row = 0; row < 8; row++) if (row !== column) {
            const factor = matrix[row]![column]!;
            for (let i = column; i <= 8; i++) matrix[row]![i]! -= factor * matrix[column]![i]!;
        }
    }
    return matrix.map(row => row[8]!);
}
export function projectReceiptPoint(coefficients: number[], u: number, v: number): { x: number; y: number } {
    const denominator = coefficients[6]! * u + coefficients[7]! * v + 1;
    if (!Number.isFinite(denominator) || Math.abs(denominator) < 1e-10) throw new Error('裁剪变换无效，请重新调整边界');
    return { x: (coefficients[0]! * u + coefficients[1]! * v + coefficients[2]!) / denominator,
        y: (coefficients[3]! * u + coefficients[4]! * v + coefficients[5]!) / denominator };
}
export async function cropReceiptForRecognition(file: File, corners: ReceiptCorners, rotation: number): Promise<File> {
    const projection = receiptProjection(corners);
    if (![0, 90, 180, 270].includes(rotation)) throw new Error('旋转角度无效');
    const bitmap = await createImageBitmap(file);
    try {
        if (!bitmap.width || !bitmap.height || bitmap.width * bitmap.height > 40000000) throw new Error('图片分辨率过大，请选用较小的原图');
        const source = document.createElement('canvas'); source.width = bitmap.width; source.height = bitmap.height;
        const sourceContext = source.getContext('2d', { willReadFrequently: true });
        if (!sourceContext) throw new Error('浏览器无法处理票据图片');
        sourceContext.drawImage(bitmap, 0, 0);
        const distance = (a: number, b: number) => Math.hypot((corners[a]!.x - corners[b]!.x) * bitmap.width, (corners[a]!.y - corners[b]!.y) * bitmap.height);
        const rawWidth = (distance(0, 1) + distance(3, 2)) / 2, rawHeight = (distance(0, 3) + distance(1, 2)) / 2;
        const scale = Math.min(1, 1280 / Math.max(rawWidth, rawHeight));
        const width = Math.max(2, Math.round(rawWidth * scale)), height = Math.max(2, Math.round(rawHeight * scale));
        const pixels = sourceContext.getImageData(0, 0, bitmap.width, bitmap.height).data;
        const cropped = document.createElement('canvas'); cropped.width = width; cropped.height = height;
        const context = cropped.getContext('2d'); if (!context) throw new Error('浏览器无法处理票据图片');
        const target = context.createImageData(width, height);
        for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
            const point = projectReceiptPoint(projection, x / (width - 1), y / (height - 1));
            const sx = Math.min(bitmap.width - 1, Math.max(0, point.x * (bitmap.width - 1))), sy = Math.min(bitmap.height - 1, Math.max(0, point.y * (bitmap.height - 1)));
            const x0 = Math.floor(sx), y0 = Math.floor(sy), x1 = Math.min(bitmap.width - 1, x0 + 1), y1 = Math.min(bitmap.height - 1, y0 + 1), dx = sx - x0, dy = sy - y0;
            for (let channel = 0; channel < 4; channel++) target.data[(y * width + x) * 4 + channel] =
                pixels[(y0 * bitmap.width + x0) * 4 + channel]! * (1 - dx) * (1 - dy) + pixels[(y0 * bitmap.width + x1) * 4 + channel]! * dx * (1 - dy)
                + pixels[(y1 * bitmap.width + x0) * 4 + channel]! * (1 - dx) * dy + pixels[(y1 * bitmap.width + x1) * 4 + channel]! * dx * dy;
        }
        context.putImageData(target, 0, 0);
        const rotated = document.createElement('canvas'); rotated.width = rotation % 180 ? height : width; rotated.height = rotation % 180 ? width : height;
        const rotatedContext = rotated.getContext('2d'); if (!rotatedContext) throw new Error('浏览器无法旋转票据图片');
        rotatedContext.fillStyle = '#ffffff'; rotatedContext.fillRect(0, 0, rotated.width, rotated.height);
        rotatedContext.translate(rotated.width / 2, rotated.height / 2); rotatedContext.rotate(rotation * Math.PI / 180); rotatedContext.drawImage(cropped, -width / 2, -height / 2);
        const blob = await new Promise<Blob>((resolve, reject) => rotated.toBlob(value => value ? resolve(value) : reject(new Error('未能生成裁剪图片')), 'image/jpeg', 0.9));
        return new File([blob], 'receipt-for-recognition.jpg', { type: 'image/jpeg' });
    } finally { bitmap.close(); }
}
