package com.posco.shiftalarm

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 마지막 보루: 잠금화면 전체화면 알림(fullScreenIntent)이 시스템에서 막혀 있을 때
 * "다른 앱 위에 표시(SYSTEM_ALERT_WINDOW)" 권한만으로 잠금화면 위에 알람 화면을 직접 띄운다.
 *
 * AlarmActivity 를 띄우지 못했을 때만(화면에 안 보일 때만) 쓰이고, 알람이 꺼지면 즉시 사라진다.
 */
object AlarmOverlay {

    private var root: View? = null
    private var manager: WindowManager? = null

    fun isShowing(): Boolean = root != null

    fun canShow(ctx: Context): Boolean = Perms.canDrawOverlay(ctx)

    fun show(ctx: Context, alarm: ShiftAlarm): Boolean {
        if (root != null) return true
        if (!canShow(ctx)) return false
        val app = ctx.applicationContext
        return try {
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = build(app, alarm)
            val type =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            wm.addView(view, lp)
            root = view
            manager = wm
            true
        } catch (e: Exception) {
            root = null
            manager = null
            false
        }
    }

    fun hide() {
        val v = root ?: return
        val m = manager
        root = null
        manager = null
        try {
            m?.removeView(v)
        } catch (e: Exception) {
        }
    }

    // ---------- 화면 구성 ----------

    private fun build(app: Context, alarm: ShiftAlarm): View {
        val d = app.resources.displayMetrics.density
        fun dp(v: Float): Int = (v * d + 0.5f).toInt()
        fun sp(v: Float): Float = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, v, app.resources.displayMetrics
        )

        val box = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#F70B0F17"))
            setPadding(dp(26f), dp(72f), dp(26f), dp(40f))
        }

        box.addView(text(app, "⏰ 알람", 13f, Color.parseColor("#FFC44D"), bold = true))
        box.addView(text(app, alarm.time, 64f, Color.parseColor("#E9EEF7"), bold = true, topDp = 10f))
        box.addView(text(app, alarm.label, 19f, Color.parseColor("#E9EEF7"), bold = true, topDp = 6f))
        box.addView(text(app, TimeFormat.dateText(alarm.ts), 13f, Color.parseColor("#8C98AC"), topDp = 8f))
        box.addView(
            text(
                app,
                "잠금화면 전체화면 알림이 꺼져 있어 알람 화면을 직접 띄웠어요.\n길게 누르지 않고 한 번 눌러 끌 수 있어요.",
                12f,
                Color.parseColor("#5F6B7E"),
                topDp = 14f
            )
        )

        val spacer = View(app)
        box.addView(
            spacer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
        )

        box.addView(
            pill(
                app, "알람 끄기", Color.parseColor("#FFC44D"), Color.parseColor("#101724")
            ) {
                AlarmService.dismiss(app)
                hide()
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(72f)
            ).apply { bottomMargin = dp(10f) }
        )

        box.addView(
            pill(
                app, "5분 뒤 다시", Color.parseColor("#1A2231"), Color.parseColor("#E9EEF7")
            ) {
                try {
                    AlarmScheduler.snooze(app, alarm)
                } catch (e: Exception) {
                }
                AlarmService.dismiss(app)
                hide()
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60f)
            )
        )

        return box
    }

    private fun text(
        ctx: Context,
        s: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        topDp: Float = 0f
    ): TextView = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.25f)
        val m = ctx.resources.displayMetrics.density
        setPadding(0, (topDp * m + 0.5f).toInt(), 0, 0)
    }

    private fun pill(
        ctx: Context,
        s: String,
        bg: Int,
        fg: Int,
        onClick: () -> Unit
    ): TextView = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(fg)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            cornerRadius = 1000f
            setColor(bg)
        }
        setOnClickListener { onClick() }
    }
}
