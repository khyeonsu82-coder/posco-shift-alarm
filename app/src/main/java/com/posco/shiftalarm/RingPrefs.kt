package com.posco.shiftalarm

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log

/**
 * 알람음 선택 결과와 "점점 크게" 설정을 저장한다 (v2.0).
 *
 * 저장 위치는 앱 내부 SharedPreferences 이고, 값은 `content://media/...` URI 문자열이다.
 * 저장된 URI 가 지워졌거나(파일 삭제·초기화) 재생에 실패하면 시스템 기본 알람음으로 돌아간다.
 */
object RingPrefs {
    private const val PREF = "shift_alarm_ring"
    private const val KEY_URI = "ringtone_uri"
    private const val KEY_TITLE = "ringtone_title"
    private const val KEY_FADE = "fade_in"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 사용자가 고른 알람음 URI(없으면 null = 기본 알람음). */
    fun uri(ctx: Context): String? = prefs(ctx).getString(KEY_URI, null)

    fun title(ctx: Context): String {
        val saved = prefs(ctx).getString(KEY_TITLE, null)
        if (!saved.isNullOrBlank()) return saved
        return "기본 알람음"
    }

    fun setRingtone(ctx: Context, uri: Uri?) {
        val e = prefs(ctx).edit()
        if (uri == null) {
            e.remove(KEY_URI).remove(KEY_TITLE)
        } else {
            e.putString(KEY_URI, uri.toString())
            e.putString(KEY_TITLE, titleOf(ctx, uri))
        }
        e.apply()
    }

    /** 실제 재생에 쓸 주소 — 고른 알람음 → 시스템 기본 알람음 → 알림음 순. */
    fun alarmUri(ctx: Context): Uri? {
        val saved = uri(ctx)?.let {
            try {
                Uri.parse(it)
            } catch (e: Exception) {
                null
            }
        }
        if (saved != null) return saved
        return defaultUri()
    }

    fun defaultUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    /** 알람음 이름(설정 화면 표시용). 읽지 못하면 "선택한 알람음". */
    fun titleOf(ctx: Context, uri: Uri): String = try {
        val r = RingtoneManager.getRingtone(ctx, uri)
        val t = r?.getTitle(ctx)
        if (t.isNullOrBlank()) "선택한 알람음" else t
    } catch (e: Exception) {
        Log.w("ShiftAlarm", "ringtone title 실패: " + e.message)
        "선택한 알람음"
    }

    fun fadeIn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FADE, false)

    fun setFadeIn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_FADE, on).apply()
    }
}
