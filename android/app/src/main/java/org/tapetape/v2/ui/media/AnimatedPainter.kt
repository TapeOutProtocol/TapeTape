package org.tapetape.v2.ui.media

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter

internal class AnimatedPainter(private val drawable: Drawable) : Painter(), RememberObserver {
    private var tick by mutableIntStateOf(0)
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            tick++
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            handler.postAtTime(what, `when`)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            handler.removeCallbacks(what)
        }
    }

    override val intrinsicSize = Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())

    override fun DrawScope.onDraw() {
        tick
        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }

    override fun onRemembered() {
        drawable.callback = callback
        (drawable as? Animatable)?.start()
    }

    override fun onForgotten() {
        (drawable as? Animatable)?.stop()
        drawable.callback = null
        handler.removeCallbacksAndMessages(null)
    }

    override fun onAbandoned() = onForgotten()
}

@Composable
internal fun rememberAnimatedPainter(path: String, maxSide: Int): Painter? {
    val drawable by produceState<Drawable?>(null, path, maxSide) { value = MediaDecode.animated(path, maxSide) }
    return drawable?.let { remember(it) { AnimatedPainter(it) } }
}
