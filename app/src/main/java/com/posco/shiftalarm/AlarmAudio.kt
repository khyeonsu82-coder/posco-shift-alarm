package com.posco.shiftalarm

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.media.MediaPlayer
import android.os.Build

/**
 * 알람 소리를 실제로 크게 만드는 부분 (v2.2).
 *
 * 삼성 등 일부 기기는 알람 스트림(STREAM_ALARM) 볼륨이 낮게 잡혀 있거나 부드러운 기본음 때문에
 * 실제 알람이 작게 들린다. 그래서
 *  1) 울리는 동안에만 폰의 알람 볼륨을 최대로 올리고, 끄면 원래 값으로 되돌린다(설정에서 끌 수 있다).
 *  2) `LoudnessEnhancer`(API 19+)로 목표 이득을 +6dB / +12dB 올린다(지원하지 않는 기기에서는 조용히 건너뛴다).
 *  3) 사용자가 고른 음량(%)을 MediaPlayer 음량으로 함께 적용한다.
 */
object AlarmAudio {

    /** 볼륨을 올리기 전의 값. -1 이면 아직 올리지 않았다. */
    private var savedStreamVolume = -1

    private fun audio(ctx: Context) =
        ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 알람 스트림 볼륨을 최대로(설정이 켜져 있을 때만). 끄면 [restoreStreamVolume] 으로 되돌린다. */
    fun raiseStreamVolume(ctx: Context) {
        if (!RingPrefs.systemVolumeMax(ctx)) return
        try {
            val am = audio(ctx)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (max <= 0) return
            if (savedStreamVolume < 0) savedStreamVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < max) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
        } catch (e: Exception) {
            // 볼륨을 못 바꾸는 기기(정책 제한)는 그냥 진행한다
        }
    }

    fun restoreStreamVolume(ctx: Context) {
        if (savedStreamVolume < 0) return
        try {
            audio(ctx).setStreamVolume(AudioManager.STREAM_ALARM, savedStreamVolume, 0)
        } catch (e: Exception) {
        }
        savedStreamVolume = -1
    }

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

    /** 사용자가 고른 음량(0~1). */
    fun playerLevel(ctx: Context): Float = (RingPrefs.volume(ctx) / 100f).coerceIn(0f, 1f)

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
