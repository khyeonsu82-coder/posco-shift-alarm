package com.posco.shiftalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * AlarmManager.setAlarmClock() 로 예정 알람을 등록한다.
 * setAlarmClock 은 Doze·절전 모드에서도 정확한 시각에 깨우고(면제),
 * 사용자에게도 시계 앱 알람처럼 표시된다.
 */
object AlarmScheduler {
    const val ACTION_FIRE = "com.posco.shiftalarm.ALARM_FIRE"

    const val EXTRA_ID = "alarm_id"
    const val EXTRA_DATE = "alarm_date"
    const val EXTRA_TIME = "alarm_time"
    const val EXTRA_LABEL = "alarm_label"
    const val EXTRA_TS = "alarm_ts"
    const val EXTRA_MODE = "alarm_mode"

    const val SNOOZE_MINUTES = 5L

    private fun am(ctx: Context) =
        ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun piFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    private fun requestCode(id: String): Int = id.hashCode() and 0x7fffffff

    private fun fireIntent(ctx: Context, alarm: ShiftAlarm): Intent =
        Intent(ctx, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            data = Uri.parse("shiftalarm://fire/" + Uri.encode(alarm.id))
            putExtra(EXTRA_ID, alarm.id)
            putExtra(EXTRA_DATE, alarm.date)
            putExtra(EXTRA_TIME, alarm.time)
            putExtra(EXTRA_LABEL, alarm.label)
            putExtra(EXTRA_TS, alarm.ts)
            putExtra(EXTRA_MODE, ShiftAlarm.modeOf(alarm.mode))
        }

    private fun firePi(ctx: Context, alarm: ShiftAlarm): PendingIntent =
        PendingIntent.getBroadcast(ctx, requestCode(alarm.id), fireIntent(ctx, alarm), piFlags())

    private fun showPi(ctx: Context, alarm: ShiftAlarm): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(ctx, requestCode("show_" + alarm.id), i, piFlags())
    }

    fun canScheduleExact(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                am(ctx).canScheduleExactAlarms()
            } catch (e: Exception) {
                false
            }
        } else true

    /** 저장된 알람 전부를 다시 등록한다(기존 등록은 취소 후). 등록 건수를 돌려준다. */
    fun scheduleAll(ctx: Context): Int {
        val list = AlarmStore.load(ctx)
        val snooze = AlarmStore.loadSnooze(ctx)
        list.forEach { cancel(ctx, it) }
        if (snooze != null) cancel(ctx, snooze)

        val now = System.currentTimeMillis()
        var count = 0
        for (a in list) {
            if (a.ts > now + 1000L) {
                place(ctx, a)
                count++
            }
        }
        if (snooze != null) {
            if (snooze.ts > now + 1000L) {
                place(ctx, snooze)
                count++
            } else {
                AlarmStore.clearSnooze(ctx)
            }
        }
        return count
    }

    fun place(ctx: Context, alarm: ShiftAlarm) {
        val manager = am(ctx)
        val pi = firePi(ctx, alarm)
        try {
            if (canScheduleExact(ctx)) {
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(alarm.ts, showPi(ctx, alarm)), pi)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.ts, pi)
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, alarm.ts, pi)
            }
        } catch (e: SecurityException) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.ts, pi)
                } else {
                    manager.set(AlarmManager.RTC_WAKEUP, alarm.ts, pi)
                }
            } catch (e2: Exception) {
                // 등록 실패해도 앱은 계속 동작한다(웹 UI는 그대로).
            }
        }
    }

    fun cancel(ctx: Context, alarm: ShiftAlarm) {
        try {
            val pi = firePi(ctx, alarm)
            am(ctx).cancel(pi)
            pi.cancel()
        } catch (e: Exception) {
            // 이미 없으면 무시
        }
    }

    /** 지금부터 5분 뒤 한 번 더 울리는 스누즈 알람. */
    fun snooze(ctx: Context, alarm: ShiftAlarm): ShiftAlarm {
        val ts = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        val snoozed = alarm.copy(id = "snooze|" + alarm.id, ts = ts)
        AlarmStore.saveSnooze(ctx, snoozed)
        place(ctx, snoozed)
        return snoozed
    }

    fun clearSnooze(ctx: Context) {
        AlarmStore.loadSnooze(ctx)?.let { cancel(ctx, it) }
        AlarmStore.clearSnooze(ctx)
    }

    /** 테스트용: N초 뒤 한 번 울리는 알람. */
    fun placeTest(ctx: Context, seconds: Int): ShiftAlarm {
        val sec = seconds.coerceIn(1, 600)
        val ts = System.currentTimeMillis() + sec * 1000L
        val alarm = ShiftAlarm("test|$ts", "test", TimeFormat.hm(ts), "테스트 알람", ts, ShiftAlarm.MODE_VIBRATE)
        place(ctx, alarm)
        return alarm
    }
}
