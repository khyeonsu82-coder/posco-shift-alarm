package com.posco.shiftalarm

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * 잠금화면 알람 화면(AlarmActivity)을 여는 경로를 한 곳에 모은다 (v1.3).
 *
 * v1.2 까지의 문제:
 *  - 알림 ID 가 4711 로 고정이라, 이전 알람 카드가 남아 있으면 시스템은 "새 알림"이 아니라
 *    "갱신"으로 보고 전체화면 알림(FSI) 판단을 건너뛴다.
 *  - 백그라운드에서 startActivity() 만 호출했다. targetSdk 34 부터는 PendingIntent 를 보내는 쪽이
 *    BAL 권한을 넘긴다는 표시(ActivityOptions.setPendingIntentBackgroundActivityStartMode)를
 *    하지 않으면 차단되고, 차단은 예외를 던지지 않아 "성공"으로 오판했다.
 */
object AlarmUi {

    /** 알람마다 새 알림 ID — 발화 1초 단위로 달라진다(같은 알람 재발화도 새 알림으로 취급). */
    fun notifId(ts: Long): Int = 47110 + ((ts / 1000L) % 100_000L).toInt()

    private fun piFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun intentFor(ctx: Context, alarm: ShiftAlarm): Intent =
        Intent(ctx, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra(AlarmScheduler.EXTRA_ID, alarm.id)
            putExtra(AlarmScheduler.EXTRA_DATE, alarm.date)
            putExtra(AlarmScheduler.EXTRA_TIME, alarm.time)
            putExtra(AlarmScheduler.EXTRA_LABEL, alarm.label)
            putExtra(AlarmScheduler.EXTRA_TS, alarm.ts)
        }

    /** 전체화면 알림(fullScreenIntent)·알림 탭이 여는 화면. */
    fun pendingIntent(ctx: Context, alarm: ShiftAlarm): PendingIntent =
        PendingIntent.getActivity(ctx, notifId(alarm.ts), intentFor(ctx, alarm), piFlags())

    /**
     * 백그라운드에서 잠금화면 위 알람 화면을 직접 띄운다.
     * 안드로이드 14(API 34)+ 에서는 BAL 권한을 명시적으로 넘겨야 실제로 뜬다.
     * (안드로이드 15+ 는 만든 쪽도 opt-in 해야 하지만, 여기서는 같은 앱이 만들어 보내므로
     *  sender opt-in 만으로 충분하다.)
     */
    fun launch(ctx: Context, alarm: ShiftAlarm): Boolean {
        val pi = pendingIntent(ctx, alarm)
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                val options: Bundle = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    .toBundle()
                pi.send(ctx, 0, null, null, null, null, options)
            } else {
                pi.send(ctx, 0, null, null, null, null)
            }
            true
        } catch (e: Exception) {
            // PendingIntent 취소·권한 오류 등 — 다른 폴백(오버레이/알림)으로 넘어간다
            try {
                ctx.startActivity(intentFor(ctx, alarm))
                true
            } catch (e2: Exception) {
                false
            }
        }
    }
}
