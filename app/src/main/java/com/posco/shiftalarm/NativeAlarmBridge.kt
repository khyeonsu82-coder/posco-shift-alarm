package com.posco.shiftalarm

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * 웹앱(index.html) ↔ 네이티브 알람 엔진 브릿지.
 * 웹에서는 window.NativeAlarm 이 없으므로 기존 웹 알람 동작이 그대로 유지된다.
 */
class NativeAlarmBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun version(): String = "1.1"

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

    /** 알림 · 정확한 알람 · 전체화면 알림 · 배터리 예외를 차례로 안내한다. */
    @JavascriptInterface
    fun requestPermissions() {
        activity.runOnUiThread { activity.requestAlarmPermissions() }
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
