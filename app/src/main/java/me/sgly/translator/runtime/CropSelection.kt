package me.sgly.translator.runtime

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

private const val MIN_CROP_SIZE = 80f

internal fun selectionBounds(
    start: Offset,
    end: Offset,
): Rect =
    Rect(
        left = minOf(start.x, end.x),
        top = minOf(start.y, end.y),
        right = maxOf(start.x, end.x),
        bottom = maxOf(start.y, end.y),
    )

internal fun Rect.isCropSelectionValid(): Boolean = width >= MIN_CROP_SIZE && height >= MIN_CROP_SIZE

internal fun Bitmap.cropTo(bounds: Rect): Bitmap? {
    val left = bounds.left.toInt().coerceIn(0, width - 1)
    val top = bounds.top.toInt().coerceIn(0, height - 1)
    var cropWidth = bounds.width.toInt()
    var cropHeight = bounds.height.toInt()

    if (left + cropWidth > width) cropWidth = width - left
    if (top + cropHeight > height) cropHeight = height - top

    return if (cropWidth > 0 && cropHeight > 0) {
        Bitmap.createBitmap(this, left, top, cropWidth, cropHeight)
    } else {
        null
    }
}
