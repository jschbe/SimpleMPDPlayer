package com.github.jschbe.simplempdplayer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.jschbe.simplempdplayer.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconTest {
    @Test fun launcherResourcesRenderAdaptiveAndThemedLayers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (id in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val icon = context.getDrawable(id) as AdaptiveIconDrawable
            assertNotNull(icon.background)
            assertNotNull(icon.foreground)
            if (Build.VERSION.SDK_INT >= 33) assertNotNull(icon.monochrome)
            val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, 192, 192)
            icon.draw(Canvas(bitmap))
            assertTrue(android.graphics.Color.alpha(bitmap.getPixel(96, 96)) > 0)
        }
    }
}
