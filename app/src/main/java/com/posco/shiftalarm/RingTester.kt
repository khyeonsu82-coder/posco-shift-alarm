package com.posco.shiftalarm

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * 설정 화면·메인의 "소리 테스트" (v2.3).
 *
 * 알람이 실제로 울릴 때와 **같은 조건**으로 6초만 재생한다:
 *  - 휴대폰의 지금 알람 볼륨(`STREAM_ALARM`) 그대로 — 슬라이더가 곧 폰 볼륨이라 추가로 건드리지 않는다.
 *    (다만 0으로 떨어져 있으면 [AlarmVolume.ensureNotMuted] 로 마지막 설정값을 되살린다 — 실제 알람과 같다.)
 *  - 선택한 부스트(`0/+6/+12dB`) · 선택한 알람음 · 시작 방식(페이드).
 */
object RingTester {
    private const val TEST_MS = 6_000L

    private var player: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var fadeTask: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopTask = Runnable { stop() }

    fun isPlaying(): Boolean = player != null

    /** 이미 재생 중이면 멈춘다(버튼을 다시 누르면 꺼짐). */
    fun toggle(ctx: Context) {
        if (player != null) stop() else play(ctx)
    }

    fun play(ctx: Context) {
        stop()
        val app = ctx.applicationContext
        val mp = AlarmAudio.newPlayer(app) ?: return
        try {
            // 실제 알람과 같은 조건: 무음(0)으로 떨어져 있으면 마지막 설정값으로 되살린다
            AlarmVolume.ensureNotMuted(app)
            enhancer = AlarmAudio.boost(app, mp.audioSessionId)
            val level = AlarmAudio.playerLevel(app)
            val start = level * AlarmAudio.startLevel(app)
            mp.setVolume(start, start)
            mp.start()
            player = mp
            val seconds = AlarmAudio.fadeSeconds(app)
            if (seconds > 0f) fadeIn(start, level, seconds)
            handler.postDelayed(stopTask, TEST_MS)
        } catch (e: Exception) {
            releasePlayer()
        }
    }

    private fun fadeIn(from: Float, to: Float, seconds: Float) {
        val steps = (seconds / 0.25f).toInt().coerceAtLeast(1)
        var step = 0
        val r = object : Runnable {
            override fun run() {
                step++
                val f = from + (to - from) * (step.toFloat() / steps)
                try {
                    player?.setVolume(f, f)
                } catch (e: Exception) {
                }
                if (step < steps) handler.postDelayed(this, 250L)
            }
        }
        fadeTask = r
        handler.postDelayed(r, 250L)
    }

    fun stop() {
        handler.removeCallbacks(stopTask)
        fadeTask?.let { handler.removeCallbacks(it) }
        fadeTask = null
        releasePlayer()
    }

    private fun releasePlayer() {
        try {
            player?.stop()
        } catch (e: Exception) {
        }
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        AlarmAudio.releaseBoost(enhancer)
        enhancer = null
    }
}
