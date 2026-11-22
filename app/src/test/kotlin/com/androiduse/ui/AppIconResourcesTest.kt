package com.androiduse.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.VectorDrawable
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import com.androiduse.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppIconResourcesTest {

    @Test
    fun `launcher adaptive icon inflates and draws all layers cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val drawable = ResourcesCompat.getDrawable(context.resources, R.mipmap.ic_launcher, context.theme)

        assertNotNull("ic_launcher should inflate", drawable)
        assertTrue("ic_launcher should be an AdaptiveIconDrawable", drawable is AdaptiveIconDrawable)
        val adaptive = drawable as AdaptiveIconDrawable
        assertTrue("background layer should be GradientDrawable", adaptive.background is GradientDrawable)
        assertTrue("foreground layer should be BitmapDrawable", adaptive.foreground is BitmapDrawable)
        assertTrue("monochrome layer should be VectorDrawable", adaptive.monochrome is VectorDrawable)

        val bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        adaptive.setBounds(0, 0, 108, 108)
        adaptive.draw(Canvas(bitmap))
    }

    @Test
    fun `round launcher adaptive icon inflates with all layers`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val drawable = ResourcesCompat.getDrawable(context.resources, R.mipmap.ic_launcher_round, context.theme)

        assertNotNull("ic_launcher_round should inflate", drawable)
        assertTrue("ic_launcher_round should be an AdaptiveIconDrawable", drawable is AdaptiveIconDrawable)
        val adaptive = drawable as AdaptiveIconDrawable
        assertTrue("background layer should be GradientDrawable", adaptive.background is GradientDrawable)
        assertTrue("foreground layer should be BitmapDrawable", adaptive.foreground is BitmapDrawable)
        assertTrue("monochrome layer should be VectorDrawable", adaptive.monochrome is VectorDrawable)
    }

    @Test
    fun `notification icon inflates and draws cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val drawable = ResourcesCompat.getDrawable(context.resources, R.drawable.ic_notification, context.theme)

        assertNotNull("ic_notification should inflate", drawable)
        assertTrue("ic_notification should be VectorDrawable", drawable is VectorDrawable)

        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        drawable!!.setBounds(0, 0, 24, 24)
        drawable.draw(Canvas(bitmap))
    }
}
