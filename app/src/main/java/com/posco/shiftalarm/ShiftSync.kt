package com.posco.shiftalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 백그라운드 자동 동기화 (v2.0).
 *
 * 앱을 열지 않아도 6시간마다 한 번씩 교대달력(hyunsu-minji-cal)의 ntfy 기록을 읽어
 * 바뀐 근무·휴가를 확인하고, Kotlin 쪽에서 14일치 알람을 다시 계산해 AlarmManager 에 재등록한다.
 *
 * 동기화 규칙(웹과 동일, 읽기 전용):
 *  - ntfy.sh/<topic>/json?poll=1 의 마지막 메시지 중 `app == "posco_hyunsu_minji_calendar"` 인 것만 쓴다.
 *  - `customShifts`(날짜별 근무 예외) · `crewHistory`(조 편성)를 저장하고 알람 계산에 사용한다.
 *  - 원본 교대달력 데이터는 절대 쓰지 않는다.
 *
 * 주기 실행은 WorkManager 의존성 없이 AlarmManager.setInexactRepeating 으로 건다(정확한 알람 권한 불필요).
 */
object ShiftSync {
    private const val PREF = "shift_alarm_sync"
    private const val KEY_CONFIG = "config_v1"
    private const val KEY_SHIFTS = "custom_v1"
    private const val KEY_CREW = "crew_v1"
    private const val KEY_LAST = "last_v1"
    private const val KEY_LAST_OK = "last_ok_v1"
    private const val KEY_TOPIC = "topic_v1"

    const val ACTION_SYNC = "com.posco.shiftalarm.SHIFT_SYNC"
    const val REQUEST_CODE = 8801
    /** 6시간마다 (하루 4회). */
    const val INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val DAYS_AHEAD = 14
    private const val MAX_TYPE_ALARMS = 6

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 웹이 넘긴 근무별 알람 규칙(topic · types · master)을 저장한다. */
    fun saveConfig(ctx: Context, json: String) {
        try {
            val o = JSONObject(json)
            prefs(ctx).edit()
                .putString(KEY_CONFIG, o.toString())
                .putString(KEY_TOPIC, o.optString("topic", ""))
                .apply()
        } catch (e: Exception) {
            Log.w("ShiftAlarm", "syncConfig 저장 실패: " + e.message)
        }
    }

    fun config(ctx: Context): JSONObject? = try {
        prefs(ctx).getString(KEY_CONFIG, null)?.let { JSONObject(it) }
    } catch (e: Exception) {
        null
    }

    fun topic(ctx: Context): String {
        val fromConfig = config(ctx)?.optString("topic", "") ?: ""
        if (fromConfig.isNotBlank()) return fromConfig
        return prefs(ctx).getString(KEY_TOPIC, "") ?: ""
    }

    fun lastSync(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST, 0L)

    fun lastOk(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LAST_OK, false)

    fun customShifts(ctx: Context): JSONObject? = try {
        prefs(ctx).getString(KEY_SHIFTS, null)?.let { JSONObject(it) }
    } catch (e: Exception) {
        null
    }

    fun crew(ctx: Context): JSONArray? = try {
        prefs(ctx).getString(KEY_CREW, null)?.let { JSONArray(it) }
    } catch (e: Exception) {
        null
    }

    /** 6시간 주기 동기화를 (다시) 건다. 부팅·앱 시작 때마다 불러도 안전하다. */
    fun schedulePeriodic(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = syncPi(ctx)
            val first = System.currentTimeMillis() + INTERVAL_MS
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, first, INTERVAL_MS, pi)
        } catch (e: Exception) {
            Log.w("ShiftAlarm", "주기 동기화 등록 실패: " + e.message)
        }
    }

    fun cancelPeriodic(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(syncPi(ctx))
            syncPi(ctx).cancel()
        } catch (e: Exception) {
        }
    }

    private fun syncPi(ctx: Context): PendingIntent {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val i = Intent(ctx, ShiftSyncReceiver::class.java).setAction(ACTION_SYNC)
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, i, flags)
    }

    /**
     * 교대달력을 읽어 근무·휴가를 갱신하고 알람을 다시 계산한다. **반드시 백그라운드 스레드에서** 부른다.
     * @return 다시 등록한 알람 수(네트워크 실패 시에도 저장된 값으로 계산한 결과).
     */
    fun refresh(ctx: Context): Int {
        fetchCloud(ctx)
        val count = rebuild(ctx)
        prefs(ctx).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
        return count
    }

    /** ntfy 에서 마지막 교대달력 메시지를 읽어 customShifts·crewHistory 를 저장한다. */
    private fun fetchCloud(ctx: Context) {
        val topic = topic(ctx)
        if (topic.isBlank()) return
        var conn: HttpURLConnection? = null
        try {
            val url = URL("https://ntfy.sh/" + URLEncoder.encode(topic, "UTF-8") + "/json?poll=1")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 12_000
                setRequestProperty("Cache-Control", "no-store")
            }
            if (conn.responseCode !in 200..299) {
                prefs(ctx).edit().putBoolean(KEY_LAST_OK, false).apply()
                return
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val lines = text.split("\n").filter { it.isNotBlank() }
            for (line in lines.asReversed()) {
                try {
                    val item = JSONObject(line)
                    if (item.optString("event") != "message") continue
                    val raw = item.optString("message", "")
                    if (raw.isBlank()) continue
                    val d = JSONObject(raw)
                    if (d.optString("app") != "posco_hyunsu_minji_calendar") continue
                    val shifts = d.optJSONObject("customShifts") ?: JSONObject()
                    val crew = d.optJSONArray("crewHistory")
                    val e = prefs(ctx).edit().putString(KEY_SHIFTS, shifts.toString())
                    if (crew != null) e.putString(KEY_CREW, crew.toString())
                    e.putBoolean(KEY_LAST_OK, true).apply()
                    return
                } catch (e: Exception) {
                    // 그 줄만 건너뛴다
                }
            }
            prefs(ctx).edit().putBoolean(KEY_LAST_OK, false).apply()
        } catch (e: Exception) {
            Log.w("ShiftAlarm", "ntfy 동기화 실패: " + e.message)
            prefs(ctx).edit().putBoolean(KEY_LAST_OK, false).apply()
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    /** 저장된 근무 규칙으로 14일치 알람을 계산해 저장하고 다시 건다. */
    fun rebuild(ctx: Context): Int {
        val list = buildAlarms(ctx)
        val old = AlarmStore.load(ctx)
        for (a in old) AlarmScheduler.cancel(ctx, a)
        AlarmStore.saveList(ctx, list)
        val placed = AlarmScheduler.scheduleAll(ctx)
        safeLog("백그라운드 동기화: 알람 " + list.size + "개 계산 · " + placed + "개 예약")
        return placed
    }

    /** 웹의 alarmsFor() 와 같은 결과 — 날짜 × 켜진 알람 수만큼, id 는 "날짜|slot_N". */
    fun buildAlarms(ctx: Context): List<ShiftAlarm> {
        val cfg = config(ctx) ?: return emptyList()
        if (cfg.has("master") && !cfg.optBoolean("master", true)) return emptyList()
        val types = cfg.optJSONObject("types") ?: return emptyList()
        val overrides = cfg.optJSONObject("overrides")
        val custom = customShifts(ctx) ?: ShiftMath.baselineJson()
        val crew = crew(ctx)
        val now = System.currentTimeMillis()
        val out = ArrayList<ShiftAlarm>()
        val today = ShiftMath.today()
        for (i in 0 until DAYS_AHEAD) {
            val date = ShiftMath.addDays(today, i)
            val type = ShiftMath.shiftType(date, custom, crew)
            // v2.1: 특근(overtime)은 알람을 잡지 않는다 — 달력·배지 표시에만 남긴다.
            if (!ShiftMath.hasAlarms(type)) continue
            val t = types.optJSONObject(type) ?: continue
            // 날짜 예외(웹의 overrides) — 그 날짜만 켜거나 첫 알람 시각을 바꾼 것
            val ov = overrides?.optJSONObject(date)
            if (!t.optBoolean("on", true) && ov?.optBoolean("on", false) != true) continue
            val arr = t.optJSONArray("alarms") ?: continue
            val slots = ArrayList<Triple<String, String, Boolean>>()
            for (j in 0 until arr.length()) {
                val a = arr.optJSONObject(j) ?: continue
                val time = a.optString("time", "")
                if (time.isBlank()) continue
                slots.add(
                    Triple(
                        time,
                        ShiftAlarm.modeOf(a.optString("mode", "")),
                        a.optBoolean("enabled", true)
                    )
                )
            }
            slots.sortBy { it.first }
            val note = ShiftMath.noteFor(date, custom)
            val label = ShiftMath.label(type, note)
            if (slots.size > MAX_TYPE_ALARMS) slots.subList(MAX_TYPE_ALARMS, slots.size).clear()
            slots.forEachIndexed { idx, s ->
                var enabled = s.third
                var time = s.first
                if (idx == 0 && ov != null) {
                    enabled = ov.optBoolean("on", true)
                    val ovTime = ov.optString("time", "")
                    if (ovTime.isNotBlank()) time = ovTime
                }
                if (!enabled) return@forEachIndexed
                val ts = ShiftMath.tsOf(date, time)
                if (ts <= now + 1000L) return@forEachIndexed
                out.add(
                    ShiftAlarm(
                        id = date + "|slot_" + (idx + 1),
                        date = date,
                        time = time,
                        label = label,
                        ts = ts,
                        mode = s.second
                    )
                )
            }
        }
        return out
    }

    private fun safeLog(msg: String) {
        try {
            Log.i("ShiftAlarm", msg)
        } catch (e: Exception) {
        }
    }
}
