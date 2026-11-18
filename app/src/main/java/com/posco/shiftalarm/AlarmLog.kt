package com.posco.shiftalarm

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject

/**
 * 마지막 알람이 실제로 어떻게 떴는지 기록한다 (v1.3 진단용).
 *
 * 설정 시트에서 "마지막 알람 · 06:24 · 잠금화면 전체화면으로 떴어요 / 알림으로만 떴어요" 를
 * 보여 주면, 어떤 권한이 막고 있는지 다음 테스트에서 바로 알 수 있다.
 */
object AlarmLog {
    private const val PREF = "shift_alarm_diag"
    private const val KEY = "last_v1"
    private val lock = Any()

    private fun read(ctx: Context): JSONObject {
        val raw = try {
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, null)
        } catch (e: Exception) {
            null
        } ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun write(ctx: Context, o: JSONObject) {
        try {
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, o.toString()).apply()
        } catch (e: Exception) {
        }
    }

    fun update(ctx: Context, block: (JSONObject) -> Unit) {
        synchronized(lock) {
            val o = read(ctx)
            block(o)
            write(ctx, o)
        }
    }

    /** 알람이 울리기 시작한 순간의 상태를 남긴다. */
    fun start(ctx: Context, alarm: ShiftAlarm) {
        val screenOn = try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive
        } catch (e: Exception) {
            false
        }
        val locked = try {
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.isKeyguardLocked
        } catch (e: Exception) {
            false
        }
        update(ctx) { o ->
            o.put("at", System.currentTimeMillis())
            o.put("time", alarm.time)
            o.put("label", alarm.label)
            o.put("test", alarm.id.startsWith("test|"))
            o.put("screenOn", screenOn)
            o.put("locked", locked)
            o.put("fsi", Perms.canUseFullScreen(ctx))
            o.put("overlayPerm", Perms.canDrawOverlay(ctx))
            o.put("notifPerm", Perms.notificationsEnabled(ctx))
            o.put("channelImportance", Perms.channelImportance(ctx))
            o.put("fullscreen", false)
            o.put("overlayShown", false)
            o.put("ended", "")
        }
    }

    /** AlarmActivity 가 실제로 화면에 올라왔다. */
    fun fullScreenShown(ctx: Context) = update(ctx) { o ->
        o.put("fullscreen", true)
        o.put("shownAt", System.currentTimeMillis())
    }

    fun overlayShown(ctx: Context) = update(ctx) { o ->
        o.put("overlayShown", true)
    }

    /** 어떤 방법으로 알람이 꺼졌는지: 화면 / 알림 / 스와이프 / 자동. */
    fun end(ctx: Context, reason: String) = update(ctx) { o ->
        o.put("ended", reason)
        o.put("endedAt", System.currentTimeMillis())
    }

    fun json(ctx: Context): JSONObject = synchronized(lock) { read(ctx) }

    /** 안드로이드 버전 — 진단 문구에 함께 보여 준다. */
    fun androidVersion(): String = Build.VERSION.RELEASE ?: ""
}
