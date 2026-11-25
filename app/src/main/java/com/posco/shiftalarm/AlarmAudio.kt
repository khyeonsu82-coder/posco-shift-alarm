package com.posco.shiftalarm

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.media.MediaPlayer
import android.os.Build

/**
 * 알람 소리를 실제로 크게 만드는 부분 (v2.3).
 *
 * v2.3 에서 바뀐 것 — **음량은 폰의 알람 볼륨이 담당한다.**
 *  - 앱 슬라이더는 `AlarmVolume` 을 통해 휴대폰의 `STREAM_ALARM` 볼륨을 직접 바꾼다(1:1).
 *    그래서 울릴 때 따로 볼륨을 올렸다가 되돌리던 v2.2 의 동작(올리기/복원)은 없앴다.
 *    슬라이더로 맞춘 폰 볼륨이 그대로 쓰인다.
 *  - `MediaPlayer` 는 항상 100%(`playerLevel` = 1.0)로 재생한다. 앱 배율을 곱하면
 *    시스템 볼륨과 이중으로 줄어들어 "100% 인데 작다"가 되기 때문이다.
 *  - 그 위에 `LoudnessEnhancer`(API 19+)로 +6dB / +12dB 부스트를 더한다(지원하지 않는 기기는 조용히 건너뛴다).
 *  - 시작 방식(바로 최대 / 3초 / 8초 페이드)은 MediaPlayer 배율을 1.0 까지 올리며 만든다.
 */
object AlarmAudio {

    /** 부스트. 지원하지 않거나 세션이 없으면 null. */
    fun boost(ctx: Context, sessionId: Int): LoudnessEnhancer? {
        val db = RingPrefs.boostDb(ctx)
        if (db <= 0 || sessionId <= 0) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return null
        return try {
            val enhancer = LoudnessEnhancer(sessionId)
            enhancer.setTargetGain(db * 100)   // 단위: mB (600 = +6dB)
            enhancer.enabled = true
            enhancer
        } catch (e: Throwable) {
            null
        }
    }

    fun releaseBoost(enhancer: LoudnessEnhancer?) {
        try {
            enhancer?.enabled = false
            enhancer?.release()
        } catch (e: Exception) {
        }
    }

    /** 페이드 시작 배율 — '바로 최대'는 1.0. */
    fun startLevel(ctx: Context): Float = when (RingPrefs.startMode(ctx)) {
        "slow" -> 0.15f
        "quick" -> 0.40f
        else -> 1f
    }

    /** 페이드 길이(초). 0 이면 페이드 없음. */
    fun fadeSeconds(ctx: Context): Float = when (RingPrefs.startMode(ctx)) {
        "slow" -> 8f
        "quick" -> 3f
        else -> 0f
    }

    /**
     * MediaPlayer 배율 — v2.3 부터 **항상 1.0**.
     * 사용자가 고른 음량은 휴대폰의 실제 알람 볼륨(STREAM_ALARM)으로 이미 적용돼 있다.
     */
    @Suppress("UNUSED_PARAMETER")
    fun playerLevel(ctx: Context): Float = 1f

    /** 고른 알람음으로 MediaPlayer 를 준비한다(실패하면 null). */
    fun newPlayer(ctx: Context): MediaPlayer? {
        val uri = RingPrefs.alarmUri(ctx) ?: return null
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(ctx.applicationContext, uri)
                prepare()
            }
        } catch (e: Exception) {
            null
        }
    }
}
