package com.posco.shiftalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 정확한 알람 시각에 AlarmManager 가 깨우는 수신부 (v1.4).
 *
 * 알람을 깨운 이 브로드캐스트(시스템이 보낸 PendingIntent)를 받은 앱은 안드로이드가 백그라운드
 * 액티비티 실행을 **잠깐 허용**한다. 그 창을 놓치지 않도록, 저장된 알람 재등록 같은 느린 일보다
 * **알람 화면 띄우기**를 먼저 한다(서비스를 거치지 않고 바로 startActivity).
 * 소리·알림은 그 다음 포그라운드 서비스가 맡고, 화면이 안 떴을 때를 대비해 서비스가 재시도한다.
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

        // ① 잠금화면 위 알람 화면 — 지금 바로(서비스를 거치지 않고) 띄운다.
        //    화면이 먼저 뜨면 AlarmActivity 가 서비스를 시작해 소리·진동이 함께 시작된다.
        AlarmUi.launch(context, alarm, viaPi = false)

        // ② 알림·소리·진동 (포그라운드 서비스) — 전체화면 알림도 여기서 발행된다.
        AlarmService.start(context, alarm)

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
