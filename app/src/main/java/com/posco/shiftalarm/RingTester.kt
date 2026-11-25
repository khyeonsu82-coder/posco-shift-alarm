package com.posco.shiftalarm

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * 설정 화면·메인의 "소리 테스트" (v2.2).
 *
 * 알람이 실제로 울릴 때와 **같은 조건**(폰 알람 볼륨 최대 · 고른 음량 · 부스트 · 시작 방식)으로
 * 6초만 재생해, 바꾼 설정을 그 자리에서 귀로 확인할 수 있게 한다. 끝나면 볼륨을 원래대로 되돌린다.
 */
object RingTester {
    private const val TEST_MS = 6_000L

    private var player: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var fadeTask: Runnable? = null
    private var appCtx: Context? = null
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
            appCtx = app
            AlarmAudio.raiseStreamVolume(app)
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
        appCtx?.let { AlarmAudio.restoreStreamVolume(it) }
        appCtx = null
    }
}
