package com.example.kwspotter

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Красная шторка на верхнюю половину экрана поверх звонка.
 * Нижняя половина остаётся свободной, чтобы положить трубку.
 */
object AlertCurtain {

    private val main = Handler(Looper.getMainLooper())
    private val showing = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    private var view: View? = null
    private var endedView: View? = null

    fun isShowing(): Boolean = showing.get()

    fun show(context: Context) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) {
            NotificationHelper.showKeywordDetected(app)
            return
        }
        if (!showing.compareAndSet(false, true)) return
        main.post {
            if (!showing.get()) return@post
            try {
                attach(app)
            } catch (_: Exception) {
                showing.set(false)
                NotificationHelper.showKeywordDetected(app)
            }
        }
    }

    /** Нижняя половина: звонок уже сброшен. */
    fun showEnded(context: Context) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) return
        if (!ended.compareAndSet(false, true)) return
        main.post {
            if (!ended.get()) return@post
            try {
                attachEnded(app)
            } catch (_: Exception) {
                ended.set(false)
            }
        }
    }

    fun hideWarning(context: Context) {
        if (!showing.getAndSet(false)) return
        val app = context.applicationContext
        main.post {
            val panel = view ?: return@post
            view = null
            try {
                app.getSystemService(WindowManager::class.java).removeView(panel)
            } catch (_: Exception) {
            }
        }
    }

    fun hide(context: Context) {
        val wasWarning = showing.getAndSet(false)
        val wasEnded = ended.getAndSet(false)
        if (!wasWarning && !wasEnded) return
        val app = context.applicationContext
        main.post { detach(app) }
    }

    @Suppress("DEPRECATION")
    private fun attach(app: Context) {
        val windowManager = app.getSystemService(WindowManager::class.java)
        val panel = curtainView(app)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            screenHeight(windowManager) / 2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }
        windowManager.addView(panel, params)
        view = panel
        wakeScreen(app)
        NotificationHelper.vibrate(app)
    }

    @Suppress("DEPRECATION")
    private fun attachEnded(app: Context) {
        val windowManager = app.getSystemService(WindowManager::class.java)
        val panel = endedView(app)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            screenHeight(windowManager) / 2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }
        windowManager.addView(panel, params)
        endedView = panel
    }

    private fun endedView(app: Context): View {
        return panel(
            app,
            intArrayOf(0xFF0C2418.toInt(), 0xFF1A5C3A.toInt()),
            app.getString(R.string.curtain_safe)
        ).apply {
            addView(rule(app, 0xFFD5EBDD.toInt()))
            addView(spacer(app, 22))
            addView(
                label(
                    app,
                    app.getString(R.string.curtain_ended),
                    22f,
                    0xFFE7F3EC.toInt(),
                    "sans-serif",
                    0.01f
                )
            )
            addView(spacer(app, 16))
            addView(
                label(
                    app,
                    app.getString(R.string.curtain_safe),
                    28f,
                    Color.WHITE,
                    "sans-serif-light",
                    0.02f
                )
            )
        }
    }

    private fun curtainView(app: Context): View {
        return panel(
            app,
            intArrayOf(0xFF161214.toInt(), 0xFF3A181C.toInt()),
            app.getString(R.string.curtain_title)
        ).apply {
            addView(rule(app, 0xFFE15B4A.toInt()))
            addView(spacer(app, 22))
            addView(
                label(
                    app,
                    app.getString(R.string.curtain_title),
                    40f,
                    0xFFF6F1EE.toInt(),
                    "sans-serif-light",
                    0.03f
                )
            )
        }
    }

    private fun panel(app: Context, colors: IntArray, description: String): LinearLayout {
        val pad = (36 * app.resources.displayMetrics.density).toInt()
        return LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
            setPadding(pad, pad, pad, pad)
            contentDescription = description
            isClickable = true
        }
    }

    private fun rule(app: Context, color: Int): View {
        val density = app.resources.displayMetrics.density
        return View(app).apply {
            background = GradientDrawable().apply {
                cornerRadius = 2f * density
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (3 * density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
    }

    private fun label(
        app: Context,
        text: String,
        sizeSp: Float,
        color: Int,
        family: String,
        letterSpacing: Float
    ): TextView {
        return TextView(app).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTypeface(Typeface.create(family, Typeface.NORMAL))
            this.letterSpacing = letterSpacing
            setLineSpacing(0f, 1.2f)
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
    }

    private fun spacer(app: Context, dp: Int): View {
        val height = (dp * app.resources.displayMetrics.density).toInt()
        return View(app).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
            )
        }
    }

    private fun detach(app: Context) {
        val windowManager = app.getSystemService(WindowManager::class.java)
        listOf(view, endedView).forEach { panel ->
            if (panel == null) return@forEach
            try {
                windowManager.removeView(panel)
            } catch (_: Exception) {
            }
        }
        view = null
        endedView = null
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(app: Context) {
        val power = app.getSystemService(PowerManager::class.java) ?: return
        val lock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "kwspotter:curtain"
        )
        lock.acquire(8_000)
    }

    @Suppress("DEPRECATION")
    private fun screenHeight(windowManager: WindowManager): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val point = android.graphics.Point()
            windowManager.defaultDisplay.getRealSize(point)
            point.y
        }
    }
}
