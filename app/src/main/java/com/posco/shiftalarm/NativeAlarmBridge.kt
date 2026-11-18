package com.posco.shiftalarm

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * 웹앱(index.html) ↔ 네이티브 알람 엔진 브릿지.
 * 웹에서는 window.NativeAlarm 이 없으므로 기존 웹 알람 동작이 그대로 유지된다.
 */
class NativeAlarmBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun version(): String = "2.0"

    /** 예정 알람 14일치를 받아 저장하고 AlarmManager 에 다시 등록한다. */
    @JavascriptInterface
    fun syncAlarms(json: String): String {
        return try {
            // 이전 목록의 알람(꺼진 날짜 포함)을 먼저 취소해야 유령 알람이 남지 않는다
            val old = AlarmStore.load(activity)
            for (a in old) AlarmScheduler.cancel(activity, a)
            AlarmStore.loadSnooze(activity)?.let { AlarmScheduler.cancel(activity, it) }

            val count = AlarmStore.save(activity, json)
            val scheduled = AlarmScheduler.scheduleAll(activity)
            AlarmStore.setLastSync(activity, System.currentTimeMillis())

            JSONObject()
                .put("ok", true)
                .put("count", count)
                .put("scheduled", scheduled)
                .toString()
        } catch (e: Exception) {
            try {
                JSONObject().put("ok", false).put("error", e.message ?: "sync error").toString()
            } catch (e2: Exception) {
                "{\"ok\":false}"
            }
        }
    }

    @JavascriptInterface
    fun getStatus(): String = activity.statusJson()

    /**
     * 근무별 알람 규칙(주간/야간 여러 개 · 진동만/소리, 동기화 코드)을 받아 저장한다 (v2.0).
     * 저장 뒤 백그라운드 스레드에서 교대달력을 한 번 확인해 알람을 다시 계산한다.
     */
    @JavascriptInterface
    fun syncConfig(json: String) {
        ShiftSync.saveConfig(activity, json)
        // 설정을 자주 바꿔도 네트워크를 반복하지 않는다(1분에 한 번까지만 확인).
        // 목록 자체는 syncAlarms 가 이미 저장·예약했으므로 여기서는 근무 규칙만 갱신한다.
        if (System.currentTimeMillis() - ShiftSync.lastSync(activity) < 60_000L) return
        try {
            Thread {
                try {
                    ShiftSync.refresh(activity.applicationContext)
                } catch (e: Exception) {
                }
            }.start()
        } catch (e: Exception) {
        }
    }

    /** 알람음 선택(RingtoneManager). */
    @JavascriptInterface
    fun pickRingtone() {
        activity.runOnUiThread { activity.pickRingtone() }
    }

    /** 점점 크게 울리기 on/off. */
    @JavascriptInterface
    fun setFadeIn(on: Boolean) {
        RingPrefs.setFadeIn(activity, on)
    }

    /** 알림 · 정확한 알람 · 전체화면 알림 · 다른 앱 위에 표시 · 배터리 예외를 차례로 안내한다. */
    @JavascriptInterface
    fun requestPermissions() {
        activity.runOnUiThread { activity.requestAlarmPermissions() }
    }

    /** 잠금화면 전체화면 알림 설정 화면으로 바로 이동(안드로이드 14+ 에서 꺼져 있을 때). */
    @JavascriptInterface
    fun openFullScreenSettings() {
        activity.runOnUiThread { activity.openFullScreenSettings() }
    }

    /** "다른 앱 위에 표시" 설정 화면으로 바로 이동. */
    @JavascriptInterface
    fun openOverlaySettings() {
        activity.runOnUiThread { activity.openOverlaySettings() }
    }

    @JavascriptInterface
    fun openExactAlarmSettings() {
        activity.runOnUiThread { activity.openExactAlarmSettings() }
    }

    @JavascriptInterface
    fun openNotificationSettings() {
        activity.runOnUiThread { activity.openNotificationSettings() }
    }

    @JavascriptInterface
    fun openBatterySettings() {
        activity.runOnUiThread { activity.openBatterySettings() }
    }

    /** N초 뒤 한 번 울리는 테스트 알람(기기에서 확인용). */
    @JavascriptInterface
    fun testAlarmIn(seconds: Int) {
        try {
            AlarmScheduler.placeTest(activity, seconds)
        } catch (e: Exception) {
        }
    }

    @JavascriptInterface
    fun cancelAll() {
        try {
            for (a in AlarmStore.load(activity)) AlarmScheduler.cancel(activity, a)
            AlarmStore.loadSnooze(activity)?.let { AlarmScheduler.cancel(activity, it) }
            AlarmStore.clear(activity)
        } catch (e: Exception) {
        }
    }

    @JavascriptInterface
    fun dismissAlarm() {
        activity.runOnUiThread { AlarmService.dismiss(activity) }
    }
}
