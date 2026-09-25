package com.finexy.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

internal data class ReceiptCrop(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object { val Default = ReceiptCrop(0.04f, 0.04f, 0.96f, 0.96f) }
}

private enum class CropDrag { TopLeft, TopRight, BottomLeft, BottomRight, Move, None }

@Composable
internal fun ReceiptImageEditor(
    source: Bitmap,
    onCancel: () -> Unit,
    onConfirm: (Bitmap, ReceiptCrop) -> Unit
) {
    var bitmap by remember(source) { mutableStateOf(source) }
    var crop by remember(source) { mutableStateOf(ReceiptCrop.Default) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val imageBounds = remember(viewport, bitmap) { fittedImageBounds(viewport, bitmap.width, bitmap.height) }
    val primary = MaterialTheme.colorScheme.primary
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Some external-camera return paths report zero dialog navigation insets for one frame.
    val safeBottom = if (bottomInset < 64.dp) 64.dp else bottomInset
    val configuration = LocalConfiguration.current
    val landscapeEndInset = if (configuration.screenWidthDp > configuration.screenHeightDp) 48.dp else 0.dp

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize().padding(top = topInset + 64.dp, end = landscapeEndInset, bottom = safeBottom + 132.dp)
                        .background(Color.Black).onSizeChanged { viewport = it },
                    contentAlignment = Alignment.Center
                ) {
                    Image(bitmap.asImageBitmap(), contentDescription = "待裁剪的票据图片", modifier = Modifier.fillMaxSize())
                    CropOverlay(
                        crop = crop,
                        imageBounds = imageBounds,
                        color = primary,
                        onCropChange = { crop = it },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(start = 8.dp, end = 8.dp + landscapeEndInset, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                    Text("裁剪票据", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                    Button(onClick = { onConfirm(bitmap, crop) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("用于识别") }
                }
                Text(
                    "拖动四角调整识别范围；旋转后会重置裁剪框。原图仅保存在本机临时目录。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(start = 20.dp, end = 20.dp + landscapeEndInset, bottom = safeBottom + 72.dp)
                )
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(start = 16.dp, end = 16.dp + landscapeEndInset, bottom = safeBottom + 8.dp).height(48.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { bitmap = rotateReceiptBitmap(bitmap); crop = ReceiptCrop.Default },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    ) { Text("向右旋转 90°") }
                    OutlinedButton(
                        onClick = { crop = ReceiptCrop.Default },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    ) { Text("重置裁剪") }
                }
            }
        }
    }
}

@Composable
private fun CropOverlay(
    crop: ReceiptCrop,
    imageBounds: Rect,
    color: Color,
    onCropChange: (ReceiptCrop) -> Unit,
    modifier: Modifier = Modifier
) {
    var drag by remember { mutableStateOf(CropDrag.None) }
    val cropBounds = crop.toPixels(imageBounds)
    val percent = { value: Float -> (value * 100).toInt() }
    Canvas(
        modifier.semantics {
            contentDescription = "裁剪区域，左${percent(crop.left)}%，上${percent(crop.top)}%，右${percent(crop.right)}%，下${percent(crop.bottom)}%。可拖动四角或使用无障碍操作调整"
            customActions = listOf(
                CustomAccessibilityAction("扩大裁剪范围") { onCropChange(crop.scaled(0.08f)); true },
                CustomAccessibilityAction("缩小裁剪范围") { onCropChange(crop.scaled(-0.08f)); true },
                CustomAccessibilityAction("向上移动裁剪框") { onCropChange(crop.translated(0f, -0.08f)); true },
                CustomAccessibilityAction("向下移动裁剪框") { onCropChange(crop.translated(0f, 0.08f)); true },
                CustomAccessibilityAction("向左移动裁剪框") { onCropChange(crop.translated(-0.08f, 0f)); true },
                CustomAccessibilityAction("向右移动裁剪框") { onCropChange(crop.translated(0.08f, 0f)); true },
                CustomAccessibilityAction("恢复默认裁剪范围") { onCropChange(ReceiptCrop.Default); true }
            )
        }
            .pointerInput(imageBounds, crop) {
                detectDragGestures(
                    onDragStart = { drag = pickDrag(it, crop.toPixels(imageBounds), 56.dp.toPx()) },
                    onDragEnd = { drag = CropDrag.None },
                    onDragCancel = { drag = CropDrag.None }
                ) { change, amount ->
                    change.consume()
                    onCropChange(crop.dragged(drag, amount, imageBounds))
                }
            }
    ) {
        if (imageBounds.width <= 0f || imageBounds.height <= 0f) return@Canvas
        val shade = Color.Black.copy(alpha = 0.58f)
        drawRect(shade, Offset(imageBounds.left, imageBounds.top), Size(imageBounds.width, cropBounds.top - imageBounds.top))
        drawRect(shade, Offset(imageBounds.left, cropBounds.bottom), Size(imageBounds.width, imageBounds.bottom - cropBounds.bottom))
        drawRect(shade, Offset(imageBounds.left, cropBounds.top), Size(cropBounds.left - imageBounds.left, cropBounds.height))
        drawRect(shade, Offset(cropBounds.right, cropBounds.top), Size(imageBounds.right - cropBounds.right, cropBounds.height))
        drawRect(color, cropBounds.topLeft, cropBounds.size, style = Stroke(width = 3.dp.toPx()))
        listOf(cropBounds.topLeft, cropBounds.topRight, cropBounds.bottomLeft, cropBounds.bottomRight).forEach {
            drawCircle(color, radius = 10.dp.toPx(), center = it)
            drawCircle(Color.White, radius = 5.dp.toPx(), center = it)
        }
    }
}

private fun fittedImageBounds(viewport: IntSize, imageWidth: Int, imageHeight: Int): Rect {
    if (viewport.width <= 0 || viewport.height <= 0 || imageWidth <= 0 || imageHeight <= 0) return Rect.Zero
    val scale = min(viewport.width.toFloat() / imageWidth, viewport.height.toFloat() / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (viewport.width - width) / 2f
    val top = (viewport.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

private fun ReceiptCrop.toPixels(bounds: Rect) = Rect(
    bounds.left + left * bounds.width,
    bounds.top + top * bounds.height,
    bounds.left + right * bounds.width,
    bounds.top + bottom * bounds.height
)

private fun pickDrag(position: Offset, bounds: Rect, radius: Float): CropDrag {
    val handles = listOf(
        CropDrag.TopLeft to bounds.topLeft,
        CropDrag.TopRight to bounds.topRight,
        CropDrag.BottomLeft to bounds.bottomLeft,
        CropDrag.BottomRight to bounds.bottomRight
    )
    return handles.minByOrNull { (_, point) -> (position - point).getDistance() }
        ?.takeIf { (_, point) -> (position - point).getDistance() <= radius }?.first
        ?: if (bounds.contains(position)) CropDrag.Move else CropDrag.None
}

private fun ReceiptCrop.dragged(mode: CropDrag, delta: Offset, bounds: Rect): ReceiptCrop {
    if (bounds.width <= 0f || bounds.height <= 0f || mode == CropDrag.None) return this
    val dx = delta.x / bounds.width
    val dy = delta.y / bounds.height
    val minSize = 0.12f
    return when (mode) {
        CropDrag.TopLeft -> copy(left = (left + dx).coerceIn(0f, right - minSize), top = (top + dy).coerceIn(0f, bottom - minSize))
        CropDrag.TopRight -> copy(right = (right + dx).coerceIn(left + minSize, 1f), top = (top + dy).coerceIn(0f, bottom - minSize))
        CropDrag.BottomLeft -> copy(left = (left + dx).coerceIn(0f, right - minSize), bottom = (bottom + dy).coerceIn(top + minSize, 1f))
        CropDrag.BottomRight -> copy(right = (right + dx).coerceIn(left + minSize, 1f), bottom = (bottom + dy).coerceIn(top + minSize, 1f))
        CropDrag.Move -> {
            val width = right - left
            val height = bottom - top
            val movedLeft = (left + dx).coerceIn(0f, 1f - width)
            val movedTop = (top + dy).coerceIn(0f, 1f - height)
            ReceiptCrop(movedLeft, movedTop, movedLeft + width, movedTop + height)
        }
        CropDrag.None -> this
    }
}

private fun ReceiptCrop.scaled(delta: Float): ReceiptCrop {
    val nextLeft = (left - delta).coerceIn(0f, 0.88f)
    val nextTop = (top - delta).coerceIn(0f, 0.88f)
    val nextRight = (right + delta).coerceIn(nextLeft + 0.12f, 1f)
    val nextBottom = (bottom + delta).coerceIn(nextTop + 0.12f, 1f)
    return ReceiptCrop(nextLeft, nextTop, nextRight, nextBottom)
}

private fun ReceiptCrop.translated(dx: Float, dy: Float): ReceiptCrop {
    val width = right - left
    val height = bottom - top
    val nextLeft = (left + dx).coerceIn(0f, 1f - width)
    val nextTop = (top + dy).coerceIn(0f, 1f - height)
    return ReceiptCrop(nextLeft, nextTop, nextLeft + width, nextTop + height)
}

internal fun decodeReceiptBitmap(context: android.content.Context, uri: Uri, maxDimension: Int = 2400): Bitmap {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: error("无法读取图片")
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: error("无法解析图片")
    val orientation = runCatching {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    return if (degrees == 0f) decoded else rotateReceiptBitmap(decoded, degrees)
}

internal fun rotateReceiptBitmap(source: Bitmap, degrees: Float = 90f): Bitmap =
    Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(degrees) }, true)

internal fun cropReceiptBitmap(source: Bitmap, crop: ReceiptCrop): Bitmap {
    val left = (crop.left.coerceIn(0f, 1f) * source.width).toInt().coerceAtMost(source.width - 1)
    val top = (crop.top.coerceIn(0f, 1f) * source.height).toInt().coerceAtMost(source.height - 1)
    val right = (crop.right.coerceIn(0f, 1f) * source.width).toInt().coerceIn(left + 1, source.width)
    val bottom = (crop.bottom.coerceIn(0f, 1f) * source.height).toInt().coerceIn(top + 1, source.height)
    return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
}

internal fun encodeReceiptForRecognition(source: Bitmap, crop: ReceiptCrop): ByteArray {
    val cropped = cropReceiptBitmap(source, crop)
    return ByteArrayOutputStream().use { output ->
        check(cropped.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "无法生成识别图片" }
        output.toByteArray()
    }
}
