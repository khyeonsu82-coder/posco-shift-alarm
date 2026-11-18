package com.posco.shiftalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 정확한 알람 시각에 AlarmManager 가 깨우는 수신부. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRE) return

        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID) ?: return
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: "알람"
        val date = intent.getStringExtra(AlarmScheduler.EXTRA_DATE) ?: ""
        val time = intent.getStringExtra(AlarmScheduler.EXTRA_TIME) ?: TimeFormat.hm(System.currentTimeMillis())
        val ts = intent.getLongExtra(AlarmScheduler.EXTRA_TS, System.currentTimeMillis())

        val alarm = ShiftAlarm(id, date, time, label, ts)

        // 지난 알람은 목록에서 빼고 남은 알람은 계속 등록된 상태로 유지한다.
        try {
            AlarmScheduler.scheduleAll(context)
        } catch (e: Exception) {
            // 재등록 실패는 무시하고 울리는 것부터 확실히 한다
        }

        AlarmService.start(context, alarm)
    }
}
