package com.finexy.mobile

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptImageEditorTest {
    @Test fun rotateAndCropProduceExpectedDimensions() {
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val rotated = rotateReceiptBitmap(source)
        assertEquals(200, rotated.width)
        assertEquals(400, rotated.height)

        val cropped = cropReceiptBitmap(source, ReceiptCrop(0.25f, 0.25f, 0.75f, 0.75f))
        assertEquals(200, cropped.width)
        assertEquals(100, cropped.height)
    }

    @Test fun encodedReceiptIsAValidJpeg() {
        val source = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888)
        val bytes = encodeReceiptForOCR(source, ReceiptCrop.Default)
        assertTrue(bytes.size > 100)
        assertEquals(0xff, bytes[0].toInt() and 0xff)
        assertEquals(0xd8, bytes[1].toInt() and 0xff)
    }
}
