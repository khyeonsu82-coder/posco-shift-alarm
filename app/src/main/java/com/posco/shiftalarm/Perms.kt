package com.posco.shiftalarm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 잠금화면 알람에 필요한 권한 상태 확인 + 설정 화면 이동을 한 곳에 모은 헬퍼.
 *
 *  - 잠금화면 전체화면 알림(USE_FULL_SCREEN_INTENT): 안드로이드 14(API 34)부터 기본 차단.
 *    꺼져 있으면 fullScreenIntent 가 무시되고 잠금화면에는 일반 알림만 뜬다.
 *  - 다른 앱 위에 표시(SYSTEM_ALERT_WINDOW): 전체화면 알림이 막혀도 잠금화면 위에 알람 화면을 띄우는 보조 수단.
 */
object Perms {

    /** 안드로이드 14+ 에서 잠금화면 전체화면 알림이 허용되어 있는지. 그 이전 버전은 항상 true. */
    fun canUseFullScreen(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.canUseFullScreenIntent()
        } catch (e: Exception) {
            // 확인 자체가 불가능한 기기/롬은 막혔다고 단정하지 않는다
            true
        }
    }

    fun canDrawOverlay(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            Settings.canDrawOverlays(ctx)
        } catch (e: Exception) {
            false
        }
    }

    /** 앱 알림이 켜져 있는지(채널 차단 포함). */
    fun notificationsEnabled(ctx: Context): Boolean = try {
        androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    } catch (e: Exception) {
        true
    }

    /** 알람 채널의 중요도. 채널이 없으면 -1. HIGH(4) 미만이면 전체화면 알림이 뜨지 않는다. */
    fun channelImportance(ctx: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return 4
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.getNotificationChannel(AlarmService.CHANNEL_ID)?.importance ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 알람 채널에 소리가 설정되어 있는지. `null`(완전 무음 채널)이면 삼성 One UI 가 전체화면
     * 알림으로 승격하지 않는다 — v1.4 는 들리지 않는 소리 리소스를 채널에 넣어 이를 피한다.
     */
    fun channelHasSound(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            val ch = nm.getNotificationChannel(AlarmService.CHANNEL_ID) ?: return false
            ch.sound != null
        } catch (e: Exception) {
            false
        }
    }

    /** 잠금화면에 알림을 표시하는 설정인지. (기기별로 항목이 없으면 true 로 본다) */
    fun lockscreenNotificationsEnabled(ctx: Context): Boolean = try {
        Settings.Secure.getInt(
            ctx.contentResolver,
            "lock_screen_show_notifications",
            1
        ) != 0
    } catch (e: Exception) {
        true
    }

    fun isIgnoringBattery(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (e: Exception) {
            false
        }
    }

    fun applyNotificationChannelState(ctx: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) true else {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
                val ch = nm.getNotificationChannel(AlarmService.CHANNEL_ID)
                ch != null && ch.importance >= android.app.NotificationManager.IMPORTANCE_HIGH
            }
        } catch (e: Exception) {
            true
        }
    }

    /** 잠금화면 전체화면 알림 설정으로 바로 보낸다. 실패하면 앱 상세 → 특수 접근 목록 순으로 내려간다. */
    fun openFullScreenSettings(ctx: Context) {
        val tries = ArrayList<Intent>()
        if (Build.VERSION.SDK_INT >= 34) {
            tries.add(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:" + ctx.packageName))
            )
        }
        tries.add(appDetails(ctx))
        openFirst(ctx, tries)
    }

    /** "다른 앱 위에 표시"(SYSTEM_ALERT_WINDOW) 설정으로 바로 보낸다. */
    fun openOverlaySettings(ctx: Context) {
        val tries = ArrayList<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            tries.add(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .setData(Uri.parse("package:" + ctx.packageName))
            )
            tries.add(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
        tries.add(appDetails(ctx))
        openFirst(ctx, tries)
    }

    fun openExactAlarmSettings(ctx: Context) {
        val tries = ArrayList<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tries.add(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:" + ctx.packageName))
            )
        }
        tries.add(appDetails(ctx))
        openFirst(ctx, tries)
    }

    fun openNotificationSettings(ctx: Context) {
        val tries = ArrayList<Intent>()
        tries.add(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        )
        tries.add(appDetails(ctx))
        openFirst(ctx, tries)
    }

    fun openBatterySettings(ctx: Context) {
        val tries = ArrayList<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            tries.add(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:" + ctx.packageName))
            )
            tries.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        tries.add(appDetails(ctx))
        openFirst(ctx, tries)
    }

    fun openAppDetails(ctx: Context) {
        openFirst(ctx, arrayListOf(appDetails(ctx)))
    }

    private fun appDetails(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:" + ctx.packageName))

    private fun openFirst(ctx: Context, tries: List<Intent>) {
        for (raw in tries) {
            val i = Intent(raw).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(i)
                return
            } catch (e: Exception) {
                // 이 기기에서 지원하지 않는 화면이면 다음 후보로
            }
        }
    }
}
