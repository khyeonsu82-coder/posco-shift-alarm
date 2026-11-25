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
 *
 * v2.2: 등록한 알람 id 를 SharedPreferences 에 기록해 두고, 다시 맞출 때 **기록된 알람까지 모두**
 * 취소한 뒤 새로 건다. 목록에서 빠진 알람(예: 날짜 예외로 06:35 만 남긴 날의 05:21)이나
 * 예전 버전이 걸어 둔 잘못된 알람이 AlarmManager 에 유령처럼 남지 않는다.
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

    private const val PREF_SCHED = "shift_alarm_sched"
    private const val KEY_IDS = "scheduled_ids_v1"

    private fun am(ctx: Context) =
        ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun schedPrefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF_SCHED, Context.MODE_PRIVATE)

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

    /**
     * 취소용 Intent. PendingIntent 의 동일성은 component·action·data 로 정해지므로
     * (extras 는 무시된다) id 만으로 같은 PendingIntent 를 찾을 수 있다.
     */
    private fun cancelIntent(ctx: Context, id: String): Intent =
        Intent(ctx, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            data = Uri.parse("shiftalarm://fire/" + Uri.encode(id))
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

    /* ---------- 등록 기록(유령 알람 방지) ---------- */

    private fun trackedIds(ctx: Context): HashSet<String> = try {
        val saved = schedPrefs(ctx).getStringSet(KEY_IDS, null)
        if (saved == null) HashSet() else HashSet(saved)
    } catch (e: Exception) {
        HashSet()
    }

    private fun setTrackedIds(ctx: Context, ids: Collection<String>) {
        try {
            schedPrefs(ctx).edit().putStringSet(KEY_IDS, HashSet(ids)).apply()
        } catch (e: Exception) {
        }
    }

    private fun track(ctx: Context, id: String) {
        try {
            val ids = trackedIds(ctx)
            if (ids.add(id)) setTrackedIds(ctx, ids)
        } catch (e: Exception) {
        }
    }

    fun trackedCount(ctx: Context): Int = trackedIds(ctx).size

    /* ---------- 등록 · 취소 ---------- */

    /**
     * 저장된 알람 전부를 다시 등록한다.
     * 먼저 **기록된 모든 알람 + 저장된 목록 + 스누즈**를 취소하므로, 목록에서 빠진 알람도 남지 않는다.
     * 등록 건수를 돌려준다.
     */
    fun scheduleAll(ctx: Context): Int {
        val list = AlarmStore.load(ctx)
        val snooze = AlarmStore.loadSnooze(ctx)

        val stale = trackedIds(ctx)
        list.forEach { stale.add(it.id) }
        if (snooze != null) stale.add(snooze.id)
        stale.forEach { cancelById(ctx, it) }

        val now = System.currentTimeMillis()
        val placed = LinkedHashSet<String>()
        for (a in list) {
            if (a.ts > now + 1000L) {
                place(ctx, a)
                placed.add(a.id)
            }
        }
        if (snooze != null) {
            if (snooze.ts > now + 1000L) {
                place(ctx, snooze)
                placed.add(snooze.id)
            } else {
                AlarmStore.clearSnooze(ctx)
            }
        }
        setTrackedIds(ctx, placed)
        return placed.size
    }

    /** 이전에 걸어 둔 알람을 기록까지 모두 지운다(앱 업데이트·목록이 크게 바뀌었을 때). */
    fun purgeAll(ctx: Context) {
        val ids = trackedIds(ctx)
        AlarmStore.load(ctx).forEach { ids.add(it.id) }
        AlarmStore.loadSnooze(ctx)?.let { ids.add(it.id) }
        ids.forEach { cancelById(ctx, it) }
        setTrackedIds(ctx, emptySet())
    }

    /** id 로 등록된 알람 취소(없으면 아무 것도 하지 않는다). */
    fun cancelById(ctx: Context, id: String) {
        if (id.isBlank()) return
        try {
            var flags = PendingIntent.FLAG_NO_CREATE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags = flags or PendingIntent.FLAG_IMMUTABLE
            }
            val pi = PendingIntent.getBroadcast(ctx, requestCode(id), cancelIntent(ctx, id), flags)
                ?: return
            am(ctx).cancel(pi)
            pi.cancel()
        } catch (e: Exception) {
            // 이미 없으면 무시
        }
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
            track(ctx, alarm.id)
        } catch (e: SecurityException) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.ts, pi)
                } else {
                    manager.set(AlarmManager.RTC_WAKEUP, alarm.ts, pi)
                }
                track(ctx, alarm.id)
            } catch (e2: Exception) {
                // 등록 실패해도 앱은 계속 동작한다(웹 UI는 그대로).
            }
        }
    }

    fun cancel(ctx: Context, alarm: ShiftAlarm) {
        cancelById(ctx, alarm.id)
        try {
            val ids = trackedIds(ctx)
            if (ids.remove(alarm.id)) setTrackedIds(ctx, ids)
        } catch (e: Exception) {
        }
    }

    /** 지금부터 5분 뒤 한 번 더 울리는 스누즈 알람. */
    fun snooze(ctx: Context, alarm: ShiftAlarm): ShiftAlarm {
        val ts = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        val snoozed = alarm.copy(id = "snooze|" + alarm.id, ts = ts)
        AlarmStore.loadSnooze(ctx)?.let { cancel(ctx, it) }
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
