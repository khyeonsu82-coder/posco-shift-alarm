package com.posco.shiftalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 정확한 알람 시각에 AlarmManager 가 깨우는 수신부 (v1.3).
 *
 * 순서를 바꿨다: 예전에는 저장된 알람을 전부 다시 등록하는 일을 먼저 했는데(SharedPreferences 읽기/쓰기 +
 * AlarmManager 재등록), 그 시간만큼 화면 표시 시도가 늦어졌다. 이제는
 * ① 포그라운드 서비스(소리·알림) → ② 잠금화면 화면 즉시 시도 → ③ 재등록(백그라운드 스레드)
 * 순으로 처리한다. setAlarmClock 으로 깨어난 직후에는 백그라운드 액티비티 실행이 잠깐 허용되는데,
 * 그 창을 놓치지 않기 위해서다.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) return

        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID) ?: return
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: "알람"
        val date = intent.getStringExtra(AlarmScheduler.EXTRA_DATE) ?: ""
        val time = intent.getStringExtra(AlarmScheduler.EXTRA_TIME) ?: TimeFormat.hm(System.currentTimeMillis())
        val ts = intent.getLongExtra(AlarmScheduler.EXTRA_TS, System.currentTimeMillis())

        val alarm = ShiftAlarm(id, date, time, label, ts)

        // ① 알림·소리 (포그라운드 서비스)
        AlarmService.start(context, alarm)

        // ② 잠금화면 위 알람 화면 — 지금 바로 시도
        AlarmUi.launch(context, alarm)

        // ③ 지난 알람은 목록에서 빼고 남은 알람은 계속 등록된 상태로 유지한다(백그라운드에서).
        try {
            Thread {
                try {
                    AlarmScheduler.scheduleAll(context)
                } catch (e: Exception) {
                }
            }.start()
        } catch (e: Exception) {
        }
    }
}
