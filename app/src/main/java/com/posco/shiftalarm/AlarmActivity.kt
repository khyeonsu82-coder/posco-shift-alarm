package com.posco.shiftalarm

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/** 잠금화면 위에 전체화면으로 뜨는 알람 화면. 스누즈(5분) / 끄기. */
class AlarmActivity : Activity() {

    private var current: ShiftAlarm? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContentView(R.layout.activity_alarm)
        bind(intent)
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopAlarm() }
        findViewById<Button>(R.id.btnSnooze).setOnClickListener { snoozeAlarm() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun bind(intent: Intent) {
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID) ?: "alarm"
        val ts = intent.getLongExtra(AlarmScheduler.EXTRA_TS, System.currentTimeMillis())
            .let { if (it > 0L) it else System.currentTimeMillis() }
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: "알람"
        val time = intent.getStringExtra(AlarmScheduler.EXTRA_TIME)
            ?.takeIf { it.isNotBlank() } ?: TimeFormat.hm(ts)
        val date = intent.getStringExtra(AlarmScheduler.EXTRA_DATE) ?: ""
        current = ShiftAlarm(id, date, time, label, ts)

        findViewById<TextView>(R.id.alarmTime).text = time
        findViewById<TextView>(R.id.alarmLabel).text = label
        findViewById<TextView>(R.id.alarmDate).text = TimeFormat.dateText(ts)
    }

    private fun stopAlarm() {
        AlarmService.dismiss(this)
        finishAndRemoveTask()
    }

    private fun snoozeAlarm() {
        val a = current
        if (a != null) {
            AlarmScheduler.snooze(this, a)
        }
        AlarmService.dismiss(this)
        finishAndRemoveTask()
    }

    @Deprecated("알람 화면에서는 뒤로가기로 닫히지 않는다 (끄기/스누즈 버튼 사용)")
    override fun onBackPressed() {
        // 알람은 명시적으로 끄거나 스누즈해야 끝난다
    }
}
