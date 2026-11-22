package com.androiduse.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.androiduse.core.CaptureResult
import com.androiduse.protocol.ImageFormat
import com.androiduse.protocol.UiBounds
import com.androiduse.protocol.UiMark
import com.androiduse.protocol.UiPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidScreenshotAnnotatorTest {

    @Test
    fun `draws bounding boxes and numbered badges onto screenshot preserving dimensions`() {
        val baseBitmap = Bitmap.createBitmap(540, 1200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val baseBytes = ByteArrayOutputStream().use { out ->
            baseBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        baseBitmap.recycle()

        val capture = CaptureResult(
            bytes = baseBytes,
            format = ImageFormat.PNG,
            mimeType = "image/png",
            width = 540,
            height = 1200,
            displayWidth = 1080,
            displayHeight = 2400,
            scale = 0.5,
            rotation = 0,
            timestampMs = 500L,
        )

        val marks = listOf(
            UiMark(
                markId = 1,
                nodeId = "n1",
                text = "Search",
                bounds = UiBounds(100, 200, 980, 340),
                center = UiPoint(540, 270),
                clickable = true,
                editable = true,
            ),
            UiMark(
                markId = 2,
                nodeId = "n2",
                contentDescription = "Settings",
                bounds = UiBounds(100, 200, 240, 340), // overlapping top-left to test badge collision avoidance
                center = UiPoint(170, 270),
                clickable = true,
            ),
        )

        val annotated = AndroidScreenshotAnnotator().annotate(capture, marks)

        assertEquals(marks, annotated.marks)
        assertEquals(540, annotated.width)
        assertEquals(1200, annotated.height)
        assertFalse("Annotated bytes should differ from blank white frame", annotated.bytes.contentEquals(baseBytes))

        val decoded = BitmapFactory.decodeByteArray(annotated.bytes, 0, annotated.bytes.size)
        assertNotNull("Annotated image should decode cleanly", decoded)
        assertEquals(540, decoded!!.width)
        assertEquals(1200, decoded.height)
        decoded.recycle()
    }
}
