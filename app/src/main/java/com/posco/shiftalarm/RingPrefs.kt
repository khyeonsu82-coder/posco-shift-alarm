package com.posco.shiftalarm

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log

/**
 * 알람음 · 음량 · 부스트 · 시작 방식 설정을 저장한다 (v2.2).
 *
 * 저장 위치는 앱 내부 SharedPreferences 이고, 알람음 값은 `content://media/...` URI 문자열이다.
 * 저장된 URI 가 지워졌거나(파일 삭제·초기화) 재생에 실패하면 시스템 기본 알람음으로 돌아간다.
 */
object RingPrefs {
    private const val PREF = "shift_alarm_ring"
    private const val KEY_URI = "ringtone_uri"
    private const val KEY_TITLE = "ringtone_title"
    private const val KEY_FADE = "fade_in"
    private const val KEY_VOL = "volume_pct_v1"
    private const val KEY_BOOST = "boost_db_v1"
    private const val KEY_START = "start_mode_v1"
    private const val KEY_SYSVOL = "system_volume_max_v1"

    /** 고를 수 있는 부스트 단계(dB). 0 = 끔. */
    val BOOST_STEPS = intArrayOf(0, 6, 12)
    val START_MODES = arrayOf("full", "quick", "slow")

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

    /** 실제 재생에 쓸 주소 — 고른 알람음 → 시스템 기본 알람음 → 벨소리 → 알림음 순. */
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

    /* ---------- 음량 · 부스트 · 시작 방식 (v2.2) ---------- */

    /** 알람 음량(%) — 10~100. 기본 100. */
    fun volume(ctx: Context): Int = prefs(ctx).getInt(KEY_VOL, 100).coerceIn(10, 100)

    fun setVolume(ctx: Context, pct: Int) {
        prefs(ctx).edit().putInt(KEY_VOL, pct.coerceIn(10, 100)).apply()
    }

    /** 소리를 더 키우는 부스트(LoudnessEnhancer 목표 이득 dB) — 0 / 6 / 12. 기본 +6dB. */
    fun boostDb(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_BOOST, 6)
        return if (BOOST_STEPS.contains(v)) v else 6
    }

    fun setBoostDb(ctx: Context, db: Int) {
        val v = if (BOOST_STEPS.contains(db)) db else 6
        prefs(ctx).edit().putInt(KEY_BOOST, v).apply()
    }

    /** 시작 방식 — "full"(바로 최대) · "quick"(3초) · "slow"(8초 점점 크게). 기본 quick. */
    fun startMode(ctx: Context): String {
        val v = prefs(ctx).getString(KEY_START, "quick")
        return if (v != null && START_MODES.contains(v)) v else "quick"
    }

    fun setStartMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString(KEY_START, if (START_MODES.contains(mode)) mode else "quick").apply()
    }

    /** 울리는 동안 폰의 알람 스트림 볼륨을 최대로 올렸다가 끄면 되돌린다(기본 켜짐). */
    fun systemVolumeMax(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SYSVOL, true)

    fun setSystemVolumeMax(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SYSVOL, on).apply()
    }

    /** v2.1 호환 — 점점 크게 = 8초 페이드. */
    fun fadeIn(ctx: Context): Boolean = startMode(ctx) != "full"

    fun setFadeIn(ctx: Context, on: Boolean) {
        // 예전 브리지 호출은 "8초 점점 크게 / 바로 최대" 두 가지만 다룬다
        setStartMode(ctx, if (on) "slow" else "full")
        prefs(ctx).edit().putBoolean(KEY_FADE, on).apply()
    }
}
