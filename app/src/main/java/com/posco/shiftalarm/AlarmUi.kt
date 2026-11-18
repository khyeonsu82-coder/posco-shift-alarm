package com.posco.shiftalarm

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * 잠금화면 알람 화면(AlarmActivity)을 여는 경로를 한 곳에 모은다 (v1.4).
 *
 * v1.3 까지의 문제:
 *  - 알림 ID 가 4711 로 고정이라, 이전 알람 카드가 남아 있으면 시스템은 "새 알림"이 아니라
 *    "갱신"으로 보고 전체화면 알림(FSI) 판단을 건너뛴다. (v1.3 에서 발화별 새 ID 로 고침)
 *  - 백그라운드에서 startActivity() 만 호출했다. targetSdk 34 부터는 PendingIntent 를 보내는 쪽이
 *    BAL 권한을 넘긴다는 표시(ActivityOptions.setPendingIntentBackgroundActivityStartMode)를
 *    하지 않으면 차단되고, 차단은 예외를 던지지 않아 "성공"으로 오판했다.
 *
 * v1.4 의 경로:
 *  ① **직접 실행** `context.startActivity()` — 알람을 깨운 브로드캐스트 직후(시스템 PendingIntent 를
 *     받은 앱은 잠깐 백그라운드 액티비티 실행이 허용된다)와, "다른 앱 위에 표시" 권한이 있는 기기에서
 *     가장 확실하다. 알람 서비스(포그라운드)에서도 같은 방법을 쓴다.
 *  ② **PendingIntent** — ①이 막힌 기기용. 34+ 에서는 BAL opt-in 옵션을 붙여 보낸다.
 *  서비스는 ①·②를 번갈아 재시도하고, 그래도 안 뜨면 오버레이로 내려간다.
 */
object AlarmUi {

    /** 알람마다 새 알림 ID — 발화 1초 단위로 달라진다(같은 알람 재발화도 새 알림으로 취급). */
    fun notifId(ts: Long): Int = 47110 + ((ts / 1000L) % 100_000L).toInt()

    private fun piFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun intentFor(ctx: Context, alarm: ShiftAlarm, mode: String = ""): Intent =
        Intent(ctx, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra(AlarmScheduler.EXTRA_ID, alarm.id)
            putExtra(AlarmScheduler.EXTRA_DATE, alarm.date)
            putExtra(AlarmScheduler.EXTRA_TIME, alarm.time)
            putExtra(AlarmScheduler.EXTRA_LABEL, alarm.label)
            putExtra(AlarmScheduler.EXTRA_TS, alarm.ts)
            putExtra(AlarmScheduler.EXTRA_MODE, ShiftAlarm.modeOf(alarm.mode))
            putExtra(EXTRA_LAUNCH_MODE, mode)
        }

    /** 알람 화면을 띄운 경로. 화면이 진단 기록에 남긴다. */
    const val EXTRA_LAUNCH_MODE = "launch_mode"

    /** 전체화면 알림(fullScreenIntent)·알림 탭이 여는 화면. */
    fun pendingIntent(ctx: Context, alarm: ShiftAlarm): PendingIntent =
        PendingIntent.getActivity(ctx, notifId(alarm.ts), intentFor(ctx, alarm), piFlags())

    /**
     * 잠금화면 위 알람 화면을 띄운다.
     *
     * @param viaPi true 면 PendingIntent 경로만, false 면 직접 실행을 먼저 시도한다.
     */
    fun launch(ctx: Context, alarm: ShiftAlarm, viaPi: Boolean = false): Boolean {
        if (!viaPi) {
            try {
                ctx.startActivity(intentFor(ctx, alarm, "direct"))
                return true
            } catch (e: Throwable) {
                // 바로 뜨지 않는 기기(백그라운드 실행 차단)는 아래 PendingIntent 경로로
            }
        }
        return try {
            val pi = pendingIntent(ctx, alarm)
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
            false
        }
    }
}
