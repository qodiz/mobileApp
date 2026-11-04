package com.example.kwspotter

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Верхняя четверть экрана поверх всех приложений, включая настройки.
 * Это окно самой службы специальных возможностей: страницы настроек прячут
 * обычные панели «поверх других приложений», а это окно оставляют.
 */
object PairingCurtain {

    private val main = Handler(Looper.getMainLooper())
    private val showing = AtomicBoolean(false)
    private var panel: View? = null
    private var hostWindow: WindowManager? = null
    private var boxes: List<TextView> = emptyList()
    private var hint: TextView? = null
    private var digits = ""
    private var busy = false

    fun show(service: AccessibilityService) {
        val task = Runnable {
            if (showing.get() && panel != null) {
                reset(service)
                return@Runnable
            }
            if (!showing.compareAndSet(false, true)) return@Runnable
            try {
                attach(service)
            } catch (e: Exception) {
                Log.e("PairingCurtain", "Could not show over settings", e)
                showing.set(false)
                panel = null
                hostWindow = null
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
    }

    fun hide(context: Context) {
        releaseView(context)
    }

    fun releaseView(context: Context) {
        showing.set(false)
        val app = context.applicationContext
        val task = Runnable { detach(app) }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
    }

    private fun attach(service: AccessibilityService) {
        val windowManager = service.getSystemService(WindowManager::class.java)
        digits = ""
        busy = false
        val view = build(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            panelHeight(service, windowManager),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(view, params)
        hostWindow = windowManager
        panel = view
    }

    private fun panelHeight(host: Context, windowManager: WindowManager): Int {
        val fromWindow = screenHeight(windowManager)
        val fromDisplay = host.resources.displayMetrics.heightPixels
        return maxOf(fromWindow, fromDisplay) / 4
    }

    private fun build(app: Context): View {
        val density = app.resources.displayMetrics.density
        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(app, 12), dp(app, 10) + topInset(app), dp(app, 12), dp(app, 10))
            background = GradientDrawable().apply {
                setColor(0xFF101828.toInt())
                cornerRadii = floatArrayOf(
                    0f, 0f,
                    0f, 0f,
                    22 * density, 22 * density,
                    22 * density, 22 * density
                )
            }
            isClickable = true
        }

        root.addView(titleRow(app))
        hint = hintLine(app).also { root.addView(it) }
        root.addView(spacer(app, 6))
        root.addView(digitRow(app), weightParams())
        root.addView(spacer(app, 6))
        root.addView(keyRow(app, listOf("1", "2", "3", "4", "5", "6")), weightParams())
        root.addView(spacer(app, 6))
        root.addView(keyRow(app, listOf("7", "8", "9", "0", "⌫")), weightParams())
        return root
    }

    private fun titleRow(app: Context): LinearLayout {
        return LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(app).apply {
                text = app.getString(R.string.pairing_notification_title)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(app).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
                val side = dp(app, 36)
                layoutParams = LinearLayout.LayoutParams(side, side)
                setOnClickListener { hide(app) }
            })
        }
    }

    private fun hintLine(app: Context): TextView {
        return TextView(app).apply {
            text = app.getString(R.string.pairing_curtain_hint)
            setTextColor(0xFFB7C3D9.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
    }

    private fun digitRow(app: Context): LinearLayout {
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val created = ArrayList<TextView>(6)
        repeat(6) {
            val box = TextView(app).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                includeFontPadding = false
                background = digitBackground(app, filled = false)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    val gap = dp(app, 4)
                    setMargins(gap, 0, gap, 0)
                }
            }
            created.add(box)
            row.addView(box)
        }
        boxes = created
        return row
    }

    private fun keyRow(app: Context, labels: List<String>): LinearLayout {
        return LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            labels.forEach { label ->
                addView(key(app, label))
            }
        }
    }

    private fun key(app: Context, label: String): TextView {
        return TextView(app).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
            includeFontPadding = false
            background = GradientDrawable().apply {
                cornerRadius = 12f * app.resources.displayMetrics.density
                setColor(if (label == "⌫") 0xFF3A2A2A.toInt() else 0xFF243044.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                val gap = dp(app, 3)
                setMargins(gap, 0, gap, 0)
            }
            setOnClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onKey(app, label)
            }
        }
    }

    private fun onKey(app: Context, label: String) {
        if (busy) return
        when (label) {
            "⌫" -> if (digits.isNotEmpty()) digits = digits.dropLast(1)
            else -> if (digits.length < 6 && label.length == 1 && label[0].isDigit()) {
                digits += label
            }
        }
        renderDigits(app)
        if (digits.length == 6) {
            busy = true
            hint?.text = app.getString(R.string.pairing_curtain_working)
            hint?.setTextColor(0xFFFFC857.toInt())
            PairingCoordinator.submitCode(app, digits) { success, detail ->
                busy = false
                if (success) {
                    hint?.text = detail
                    hint?.setTextColor(0xFF7DFFB3.toInt())
                    main.postDelayed({ hide(app) }, 900)
                } else {
                    digits = ""
                    renderDigits(app)
                    hint?.text = detail
                    hint?.setTextColor(0xFFFF8A80.toInt())
                }
            }
        }
    }

    private fun renderDigits(app: Context) {
        boxes.forEachIndexed { index, box ->
            val symbol = digits.getOrNull(index)?.toString().orEmpty()
            box.text = symbol
            box.background = digitBackground(app, symbol.isNotEmpty())
        }
    }

    private fun reset(app: Context) {
        digits = ""
        busy = false
        renderDigits(app)
        hint?.text = app.getString(R.string.pairing_curtain_hint)
        hint?.setTextColor(0xFFB7C3D9.toInt())
    }

    private fun digitBackground(app: Context, filled: Boolean): GradientDrawable {
        val density = app.resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = 12f * density
            setColor(0xFF1A2436.toInt())
            setStroke(
                ((if (filled) 2f else 1f) * density).toInt().coerceAtLeast(1),
                if (filled) 0xFFFFC857.toInt() else 0xFF3D4C66.toInt()
            )
        }
    }

    private fun weightParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
    }

    private fun spacer(app: Context, sizeDp: Int): View {
        return View(app).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(app, sizeDp)
            )
        }
    }

    private fun detach(app: Context) {
        val windowManager = hostWindow ?: app.getSystemService(WindowManager::class.java)
        val current = panel
        panel = null
        hostWindow = null
        boxes = emptyList()
        hint = null
        digits = ""
        busy = false
        if (current == null) return
        try {
            windowManager.removeView(current)
        } catch (_: Exception) {
        }
    }

    private fun dp(app: Context, value: Int): Int {
        return (value * app.resources.displayMetrics.density).toInt()
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

    private fun topInset(app: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        val windowManager = app.getSystemService(WindowManager::class.java)
        return windowManager.currentWindowMetrics.windowInsets
            .getInsets(android.view.WindowInsets.Type.statusBars())
            .top
    }
}
