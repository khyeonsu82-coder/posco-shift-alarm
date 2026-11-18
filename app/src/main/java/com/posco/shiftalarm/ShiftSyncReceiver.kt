package com.posco.shiftalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 6시간마다 AlarmManager 가 깨우는 백그라운드 동기화 수신부 (v2.0).
 *
 * 네트워크와 알람 재등록은 느리므로 goAsync 로 브로드캐스트 수명을 잠깐 늘려 워커 스레드에서 처리한다.
 */
class ShiftSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != null && intent.action != ShiftSync.ACTION_SYNC) return
        val pending = goAsync()
        try {
            Thread {
                try {
                    ShiftSync.refresh(context.applicationContext)
                } catch (e: Exception) {
                } finally {
                    try {
                        pending.finish()
                    } catch (e: Exception) {
                    }
                }
            }.start()
        } catch (e: Exception) {
            try {
                pending.finish()
            } catch (e2: Exception) {
            }
        }
    }
}
