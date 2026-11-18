package com.posco.shiftalarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** 웹앱(index.html)이 넘겨준 예정 알람 한 건. */
data class ShiftAlarm(
    val id: String,
    val date: String,
    val time: String,
    val label: String,
    val ts: Long
) {
    /** 설정의 "10초 뒤 테스트 알람" — 벨소리 없이 짧은 진동만 울린다(v1.4). */
    fun isTest(): Boolean = id.contains("test|")
}

/** SharedPreferences 저장소: 예정 알람 목록 + 스누즈 1건. */
object AlarmStore {
    private const val PREF = "shift_alarm_store"
    private const val KEY_ALARMS = "alarms_v1"
    private const val KEY_SNOOZE = "snooze_v1"
    private const val KEY_LAST_SYNC = "last_sync_v1"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<ShiftAlarm> {
        val raw = prefs(ctx).getString(KEY_ALARMS, null) ?: return emptyList()
        val out = ArrayList<ShiftAlarm>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val ts = o.optLong("ts", 0L)
                if (id.isBlank() || ts <= 0L) continue
                out.add(
                    ShiftAlarm(
                        id = id,
                        date = o.optString("date", ""),
                        time = o.optString("time", ""),
                        label = o.optString("label", ""),
                        ts = ts
                    )
                )
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }

    /** 웹에서 온 JSON 배열을 검증해 저장한다. 저장된 건수를 돌려준다. */
    fun save(ctx: Context, json: String): Int {
        val arr = JSONArray(json)
        val clean = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val ts = o.optLong("ts", 0L)
            if (id.isBlank() || ts <= 0L) continue
            val item = JSONObject()
                .put("id", id)
                .put("date", o.optString("date", ""))
                .put("time", o.optString("time", ""))
                .put("label", o.optString("label", ""))
                .put("ts", ts)
            clean.put(item)
        }
        prefs(ctx).edit().putString(KEY_ALARMS, clean.toString()).apply()
        return clean.length()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_ALARMS).remove(KEY_SNOOZE).apply()
    }

    fun saveSnooze(ctx: Context, alarm: ShiftAlarm) {
        val o = JSONObject()
            .put("id", alarm.id)
            .put("date", alarm.date)
            .put("time", alarm.time)
            .put("label", alarm.label)
            .put("ts", alarm.ts)
        prefs(ctx).edit().putString(KEY_SNOOZE, o.toString()).apply()
    }

    fun loadSnooze(ctx: Context): ShiftAlarm? {
        val raw = prefs(ctx).getString(KEY_SNOOZE, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val id = o.optString("id", "")
            val ts = o.optLong("ts", 0L)
            if (id.isBlank() || ts <= 0L) null
            else ShiftAlarm(id, o.optString("date", ""), o.optString("time", ""), o.optString("label", ""), ts)
        } catch (e: Exception) {
            null
        }
    }

    fun clearSnooze(ctx: Context) {
        prefs(ctx).edit().remove(KEY_SNOOZE).apply()
    }

    fun lastSync(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_SYNC, 0L)

    fun setLastSync(ctx: Context, at: Long) {
        prefs(ctx).edit().putLong(KEY_LAST_SYNC, at).apply()
    }
}

/** 잠금화면에 보여 줄 시각 문구. */
object TimeFormat {
    private val WD = arrayOf("일", "월", "화", "수", "목", "금", "토")

    fun hm(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    fun dateText(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val wd = WD.getOrElse(c.get(Calendar.DAY_OF_WEEK) - 1) { "" }
        return String.format(
            Locale.US, "%d/%d (%s)",
            c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), wd
        )
    }
}
