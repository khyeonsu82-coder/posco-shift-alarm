package com.posco.shiftalarm

import android.animation.ObjectAnimator
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 잠금화면 위에 전체화면으로 뜨는 알람 화면 (v1.2).
 *
 * v1.1 까지 잠금화면에서 바로 안 뜨던 원인:
 *  - 안드로이드 14(targetSdk 34)에서 USE_FULL_SCREEN_INTENT 가 기본 차단이라 fullScreenIntent 가 무시됨
 *  - 화면이 켜지고 잠금화면 위에 그려지도록 하는 처리가 legacy 플래그에만 의존
 * 수정: 실행 즉시 setShowWhenLocked/setTurnScreenOn + KEEP_SCREEN_ON + ALLOW_LOCK_WHILE_SCREEN_ON,
 *       끄기 버튼은 잠금 해제 없이 길게 눌러 바로 끌 수 있고, 실수 방지를 위해 1.2초 홀드.
 */
class AlarmActivity : Activity() {

    companion object {
        const val ACTION_FINISH = "com.posco.shiftalarm.ALARM_UI_FINISH"
        private const val HOLD_MS = 1200L

        /** 알람 화면이 실제로 떠 있는지 — 서비스가 폴백(직접 띄우기/오버레이) 판단에 쓴다. */
        @Volatile
        var onScreen: Boolean = false
            private set

        fun closeAll(ctx: Context) {
            try {
                ctx.sendBroadcast(Intent(ACTION_FINISH).setPackage(ctx.packageName))
            } catch (e: Exception) {
            }
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val holdDone = Runnable { stopAlarm() }
    private var stopAnimator: ObjectAnimator? = null
    private var stopFill: View? = null
    private var stopLabel: TextView? = null
    private var current: ShiftAlarm? = null
    private var receiverOn = false

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == ACTION_FINISH) closeQuietly()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContentView(R.layout.activity_alarm)
        setVolumeControlStream(AudioManager.STREAM_ALARM)

        bind(intent)
        setupStop(findViewById(R.id.stopBox))
        findViewById<Button>(R.id.btnSnooze).setOnClickListener { snoozeAlarm() }
        findViewById<Button>(R.id.btnUnlock).setOnClickListener { unlockPhone() }
        showPermissionNote()
        registerFinishReceiver()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
        showPermissionNote()
    }

    // ---------- 잠금화면 위 표시 ----------

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val attrs = window.attributes
                attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = attrs
            } catch (e: Exception) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        onScreen = true
    }

    override fun onStop() {
        super.onStop()
        onScreen = false
    }

    override fun onDestroy() {
        onScreen = false
        ui.removeCallbacks(holdDone)
        unregisterFinishReceiver()
        try {
            stopAnimator?.cancel()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    // ---------- 데이터 ----------

    private fun bind(intent: Intent?) {
        val i = intent ?: return
        val id = i.getStringExtra(AlarmScheduler.EXTRA_ID) ?: "alarm"
        val ts = i.getLongExtra(AlarmScheduler.EXTRA_TS, System.currentTimeMillis())
            .let { if (it > 0L) it else System.currentTimeMillis() }
        val label = i.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: "알람"
        val time = i.getStringExtra(AlarmScheduler.EXTRA_TIME)
            ?.takeIf { it.isNotBlank() } ?: TimeFormat.hm(ts)
        val date = i.getStringExtra(AlarmScheduler.EXTRA_DATE) ?: ""
        current = ShiftAlarm(id, date, time, label, ts)

        findViewById<TextView>(R.id.alarmTime).text = time
        findViewById<TextView>(R.id.alarmLabel).text = label
        findViewById<TextView>(R.id.alarmDate).text = TimeFormat.dateText(ts)
    }

    /** 전체화면 알림이 꺼져 있으면 이 화면이 왜 알림에서 열렸는지 알려 주고 바로 설정으로 보낸다. */
    private fun showPermissionNote() {
        val note = findViewById<TextView>(R.id.alarmPermNote)
        if (Perms.canUseFullScreen(this)) {
            note.visibility = View.GONE
            return
        }
        note.visibility = View.VISIBLE
        note.text = getString(R.string.alarm_perm_note)
        note.setOnClickListener { Perms.openFullScreenSettings(this) }
    }

    private fun unlockPhone() {
        try {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!km.isKeyguardLocked) return
            km.requestDismissKeyguard(this, null)
        } catch (e: Exception) {
        }
    }

    // ---------- 길게 눌러 끄기 ----------

    private fun setupStop(box: FrameLayout) {
        val fill = findViewById<View>(R.id.stopFill)
        val label = findViewById<TextView>(R.id.stopLabel)
        stopFill = fill
        stopLabel = label
        fill.pivotX = 0f
        fill.scaleX = 0f

        val anim = ObjectAnimator.ofFloat(fill, View.SCALE_X, 0f, 1f)
        anim.duration = HOLD_MS
        stopAnimator = anim

        box.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    startHold()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    cancelHold()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    cancelHold()
                    true
                }
                else -> false
            }
        }
        // 손가락 터치로는 위 OnTouchListener 가 처리하고, 이 클릭은 접근성(토크백)용이다.
        box.setOnClickListener { stopAlarm() }

        try {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!km.isKeyguardLocked) findViewById<Button>(R.id.btnUnlock).visibility = View.GONE
        } catch (e: Exception) {
        }
    }

    private fun startHold() {
        ui.removeCallbacks(holdDone)
        try {
            stopAnimator?.cancel()
            stopFill?.scaleX = 0f
            stopAnimator?.start()
        } catch (e: Exception) {
        }
        stopLabel?.text = getString(R.string.btn_stop_holding)
        ui.postDelayed(holdDone, HOLD_MS)
    }

    private fun cancelHold() {
        ui.removeCallbacks(holdDone)
        try {
            stopAnimator?.cancel()
        } catch (e: Exception) {
        }
        stopFill?.scaleX = 0f
        stopLabel?.text = getString(R.string.btn_stop_hold)
    }

    // ---------- 끄기 / 스누즈 ----------

    private fun stopAlarm() {
        ui.removeCallbacks(holdDone)
        onScreen = false
        AlarmOverlay.hide()
        AlarmService.dismiss(this)
        closeQuietly()
    }

    private fun snoozeAlarm() {
        val a = current
        if (a != null) {
            try {
                AlarmScheduler.snooze(this, a)
            } catch (e: Exception) {
            }
        }
        onScreen = false
        AlarmOverlay.hide()
        AlarmService.dismiss(this)
        closeQuietly()
    }

    private fun closeQuietly() {
        try {
            finishAndRemoveTask()
        } catch (e: Exception) {
            finish()
        }
    }

    private fun registerFinishReceiver() {
        if (receiverOn) return
        try {
            ContextCompat.registerReceiver(
                this,
                finishReceiver,
                IntentFilter(ACTION_FINISH),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverOn = true
        } catch (e: Exception) {
            receiverOn = false
        }
    }

    private fun unregisterFinishReceiver() {
        if (!receiverOn) return
        receiverOn = false
        try {
            unregisterReceiver(finishReceiver)
        } catch (e: Exception) {
        }
    }

    @Deprecated("알람 화면에서는 뒤로가기로 닫히지 않는다 (길게 눌러 끄기 / 5분 뒤 다시 사용)")
    override fun onBackPressed() {
        // 알람은 명시적으로 끄거나 스누즈해야 끝난다
    }
}
