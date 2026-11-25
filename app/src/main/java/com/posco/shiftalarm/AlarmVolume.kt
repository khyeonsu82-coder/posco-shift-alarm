package com.posco.shiftalarm

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject

/**
 * 휴대폰의 **실제 알람 스트림 볼륨**(`AudioManager.STREAM_ALARM`)을 읽고 쓰는 부분 (v2.3).
 *
 * v2.2 까지는 앱의 `음량 (10~100%)` 슬라이더가 `MediaPlayer.setVolume()`(앱 내부 배율)만 만졌기 때문에,
 * 휴대폰의 시스템 알람 볼륨이 낮으면 앱에서 100% 로 올려도 그 한계에 갇혀 소리가 작았다.
 * v2.3 부터는 **슬라이더 = 휴대폰 알람 볼륨(1:1)** 이다.
 *
 *  - 설정 시트를 열면 [current] 로 지금 폰의 알람 볼륨(예: 7/15)을 읽어 슬라이더에 그대로 보여 준다.
 *  - 슬라이더를 움직이면 [setPercent] 가 곧바로 `setStreamVolume(STREAM_ALARM, index, 0)` 으로 실제 폰 볼륨을 바꾼다.
 *  - 100% 는 `getStreamMaxVolume(STREAM_ALARM)` = 폰이 낼 수 있는 최대치이고, 재생은 `MediaPlayer` 100% 로 한다.
 *
 * 삼성 등 일부 기기는 알람 볼륨을 앱에서 바꾸지 못하게 막아 두기도 한다(정책·무음 모드). 그때는
 * 예외를 삼키고 **바뀐 뒤의 실제 값**을 돌려주므로, 웹 슬라이더가 실제 값으로 되돌아가고 안내 문구가 나온다.
 */
object AlarmVolume {

    /** 실제 알람 볼륨 한 지점. [pct] 는 0~100 환산, [index] 는 0~[max] 단계. */
    data class Level(val pct: Int, val index: Int, val max: Int) {
        fun toJson(): String = JSONObject()
            .put("pct", pct)
            .put("index", index)
            .put("max", max)
            .put("muted", index <= 0)
            .toString()
    }

    private fun audio(ctx: Context): AudioManager? = try {
        ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (e: Exception) {
        null
    }

    /** 지금 폰의 알람 볼륨. 읽지 못하면 0/0. */
    fun current(ctx: Context): Level {
        val am = audio(ctx) ?: return Level(0, 0, 0)
        return try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (max <= 0) return Level(0, 0, 0)
            val idx = am.getStreamVolume(AudioManager.STREAM_ALARM).coerceIn(0, max)
            Level(percentOf(idx, max), idx, max)
        } catch (e: Exception) {
            Level(0, 0, 0)
        }
    }

    /** 단계 → % (반올림). 7/15 → 47%. */
    fun percentOf(index: Int, max: Int): Int {
        if (max <= 0) return 0
        return Math.round(index * 100f / max).coerceIn(0, 100)
    }

    /** % → 단계 (반올림). 100% → max, 47% → 7/15. */
    fun indexOf(pct: Int, max: Int): Int {
        if (max <= 0) return 0
        return Math.round(pct.coerceIn(0, 100) * max / 100f).coerceIn(0, max)
    }

    /**
     * 슬라이더 %를 폰의 실제 알람 볼륨으로 바꾼다. 바뀐 **실제 값**을 돌려준다.
     * (기기가 막아 두면 요청한 값이 아니라 지금 값이 그대로 돌아온다.)
     */
    fun setPercent(ctx: Context, pct: Int): Level {
        val am = audio(ctx) ?: return current(ctx)
        val want = pct.coerceIn(0, 100)
        return try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (max <= 0) return current(ctx)
            val target = indexOf(want, max)
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) != target) {
                // 0 = 시스템 볼륨 UI 를 띄우지 않는다(앱 슬라이더가 곧 표시)
                am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            }
            current(ctx)
        } catch (e: Exception) {
            current(ctx)
        }
    }

    /**
     * 울리기 직전 안전장치 — 폰 알람 볼륨이 0(무음)으로 떨어져 있는데 마지막으로 맞춘 값은 0이 아니면,
     * 그 값으로 되살린다. 사용자가 슬라이더로 **일부러 0** 을 골라 두었으면(저장값 0) 손대지 않는다.
     */
    fun ensureNotMuted(ctx: Context): Boolean {
        val want = RingPrefs.volume(ctx)
        if (want <= 0) return false
        val now = current(ctx)
        if (now.max <= 0 || now.index > 0) return false
        val after = setPercent(ctx, want)
        return after.index > 0
    }
}
