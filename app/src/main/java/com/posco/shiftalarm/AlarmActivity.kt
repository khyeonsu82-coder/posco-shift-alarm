package com.posco.shiftalarm

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 잠금화면 위에 전체화면으로 뜨는 알람 화면 (v1.4).
 *
 * v1.4 에서 바꾼 것:
 *  - 끄기 버튼을 **한 번 탭**으로 바꿨다(1.2초 길게 누르기 제거). 잠금을 풀지 않고 바로 꺼진다.
 *  - 이 화면이 알람 서비스보다 먼저 뜬 경우(전체화면 알림·직접 실행), 화면이 서비스를 시작해
 *    소리·진동이 화면과 함께 시작되도록 했다(서비스가 이미 그 알람으로 돌고 있으면 다시 시작하지 않는다).
 *
 * 잠금화면 위 표시: setShowWhenLocked / setTurnScreenOn (API 27+) + KEEP_SCREEN_ON + ALLOW_LOCK_WHILE_SCREEN_ON.
 */
class AlarmActivity : Activity() {

    companion object {
        const val ACTION_FINISH = "com.posco.shiftalarm.ALARM_UI_FINISH"

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
        // 폴백 판단(서비스)이 이 화면이 떴는지 알 수 있도록 onCreate 에서부터 표시한다
        onScreen = true
        setContentView(R.layout.activity_alarm)
        setVolumeControlStream(AudioManager.STREAM_ALARM)

        bind(intent)
        setupStop(findViewById(R.id.stopBox))
        findViewById<Button>(R.id.btnSnooze).setOnClickListener { snoozeAlarm() }
        findViewById<Button>(R.id.btnUnlock).setOnClickListener { unlockPhone() }
        showPermissionNote()
        registerFinishReceiver()
        // 진단: 마지막 알람이 잠금화면 전체화면으로 떴다는 기록
        try {
            AlarmLog.fullScreenShown(this)
        } catch (e: Exception) {
        }
        // 화면이 먼저 떴을 때(전체화면 알림·직접 실행)도 소리·진동이 함께 시작되게 한다.
        ensureAlarmRunning()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
        showPermissionNote()
        ensureAlarmRunning()
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
        // 일부 기기/롬에서 잠금화면 위 표시 플래그가 초기화되므로 다시 걸어 둔다
        showOverLockScreen()
    }

    override fun onStop() {
        super.onStop()
        onScreen = false
    }

    override fun onDestroy() {
        onScreen = false
        unregisterFinishReceiver()
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

        // 어떤 경로(직접 실행 / 전체화면 알림)로 이 화면이 떴는지 진단 기록에 남긴다
        val mode = i.getStringExtra(AlarmUi.EXTRA_LAUNCH_MODE) ?: ""
        if (mode.isNotEmpty()) {
            try {
                AlarmLog.startMode(this, mode)
            } catch (e: Exception) {
            }
        }

        findViewById<TextView>(R.id.alarmTime).text = time
        findViewById<TextView>(R.id.alarmLabel).text = label
        findViewById<TextView>(R.id.alarmDate).text = TimeFormat.dateText(ts)
    }

    /**
     * 이 화면만 떠 있고 알람 서비스가 아직 그 알람으로 돌지 않았으면 서비스를 시작한다.
     * (전체화면 알림을 눌러 열었거나, 화면이 서비스보다 먼저 뜬 경우 소리가 빠지지 않게)
     */
    private fun ensureAlarmRunning() {
        val a = current ?: return
        try {
            if (AlarmService.activeTs != a.ts) AlarmService.start(this, a)
        } catch (e: Exception) {
        }
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

    // ---------- 한 번 눌러 끄기 ----------

    private fun setupStop(box: View) {
        // v1.4: 길게 누르기 대신 한 번 탭으로 즉시 끈다(스누즈 버튼은 그대로).
        box.setOnClickListener { stopAlarm() }
        try {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!km.isKeyguardLocked) findViewById<Button>(R.id.btnUnlock).visibility = View.GONE
        } catch (e: Exception) {
        }
    }

    // ---------- 끄기 / 스누즈 ----------

    private fun stopAlarm() {
        onScreen = false
        AlarmOverlay.hide()
        AlarmService.dismiss(this, "화면")
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
        AlarmService.dismiss(this, "화면")
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

    @Deprecated("알람 화면에서는 뒤로가기로 닫히지 않는다 (한 번 눌러 끄기 / 5분 뒤 다시 사용)")
    override fun onBackPressed() {
        // 알람은 명시적으로 끄거나 스누즈해야 끝난다
    }
}
