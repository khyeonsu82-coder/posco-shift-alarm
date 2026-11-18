package com.posco.shiftalarm

import android.content.Context
import android.text.TextUtils
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 교대 근무 계산 (v2.0) — 웹(index.html)의 규칙을 Kotlin 으로 그대로 옮겼다.
 *
 * 앱을 열지 않아도 백그라운드 동기화가 근무를 계산해 알람을 다시 걸 수 있어야 하므로,
 * 4조2교대 8일 주기 · 과거 사이클 · 교대달력의 날짜별 예외(customShifts) · 조 편성(crewHistory)을
 * 웹과 같은 값으로 계산한다.
 */
object ShiftMath {

    private val TEAM_CYCLES = mapOf(
        "A" to listOf("day", "day", "off", "off", "night", "night", "off", "off"),
        "B" to listOf("off", "off", "day", "day", "off", "off", "night", "night"),
        "C" to listOf("off", "off", "night", "night", "off", "off", "day", "day"),
        "D" to listOf("night", "night", "off", "off", "day", "day", "off", "off")
    )
    private val HIST_CYCLE = listOf("day", "day", "off", "off", "night", "night", "off", "off")
    private const val HIST_UNTIL = "2026-01-15"

    /** 2026-11-09 = index 0 (교대달력과 동일 기준). */
    private val GLOBAL_BASE = utcDay(2026, 11, 9)
    /** 과거 사이클 기준 2023-09-08. */
    private val HIST_ANCHOR = utcDay(2023, 9, 8)

    private val DF = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    /**
     * 교대달력의 2026-11-18 시점 실제 상태 스냅샷(웹 BASELINE_SHIFTS 와 동일).
     * ntfy 보관이 비어 있을 때(새 설치·보관 만료)의 안전망으로만 쓴다.
     */
    private val BASELINE = mapOf(
        "2026-11-07" to ("off" to ""),
        "2026-11-09" to ("night" to ""),
        "2026-11-14" to ("night" to "OT X"),
        "2026-11-17" to ("night" to ""),
        "2026-11-20" to ("off" to "휴가"),
        "2026-11-22" to ("night" to ""),
        "2026-11-26" to ("day" to "평생학습"),
        "2026-11-29" to ("day" to ""),
        "2026-12-11" to ("off" to "6시 백운프라자 (포공)"),
        "2026-12-15" to ("day" to ""),
        "2026-12-19" to ("night" to ""),
        "2026-12-23" to ("day" to "")
    )

    /** ntfy 보관이 비었을 때 쓰는 기본 예외(날짜 → 근무/메모). */
    fun baselineJson(): JSONObject {
        val o = JSONObject()
        for ((date, v) in BASELINE) {
            o.put(date, JSONObject().put("type", v.first).put("note", v.second))
        }
        return o
    }

    private val LABELS = mapOf(
        "day" to "주간",
        "night" to "야간",
        "off" to "휴무",
        "overtime" to "특근"
    )

    fun today(): String = DF.format(Date())

    fun addDays(date: String, days: Int): String {
        val c = Calendar.getInstance()
        c.time = parse(date) ?: Date()
        c.add(Calendar.DAY_OF_MONTH, days)
        return DF.format(c.time)
    }

    fun parse(date: String): Date? = try {
        DF.parse(date)
    } catch (e: Exception) {
        null
    }

    /** 날짜 + "HH:mm" → 로컬 시각(ms). */
    fun tsOf(date: String, time: String): Long {
        val d = parse(date) ?: Date()
        val parts = time.split(":")
        val hh = parts.getOrNull(0)?.toIntOrNull() ?: 7
        val mm = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val c = Calendar.getInstance()
        c.time = d
        c.set(Calendar.HOUR_OF_DAY, hh)
        c.set(Calendar.MINUTE, mm)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** crewHistory([{effectiveDate,team}]) 를 날짜에 맞춰 적용해 조를 고른다. */
    fun teamFor(date: String, crew: JSONArray?): String {
        if (crew == null || crew.length() == 0) return "B"
        val list = ArrayList<Pair<String, String>>()
        for (i in 0 until crew.length()) {
            val o = crew.optJSONObject(i) ?: continue
            val eff = o.optString("effectiveDate", "")
            val team = o.optString("team", "")
            if (eff.isNotEmpty() && TEAM_CYCLES.containsKey(team)) list.add(eff to team)
        }
        if (list.isEmpty()) return "B"
        list.sortBy { it.first }
        var team = list.first().second
        for ((eff, t) in list) if (date >= eff) team = t
        return if (TEAM_CYCLES.containsKey(team)) team else "B"
    }

    fun baseShift(date: String, crew: JSONArray?): String {
        if (date <= HIST_UNTIL) {
            val diff = dayDiff(date, HIST_ANCHOR)
            return HIST_CYCLE[((diff % 8) + 8) % 8]
        }
        val diff = dayDiff(date, GLOBAL_BASE)
        val cycle = TEAM_CYCLES[teamFor(date, crew)] ?: TEAM_CYCLES.getValue("B")
        return cycle[((diff % 8) + 8) % 8]
    }

    /** 교대달력의 날짜별 예외(customShifts)를 적용한 실제 근무 종류. */
    fun shiftType(date: String, custom: JSONObject?, crew: JSONArray?): String {
        val o = custom?.optJSONObject(date)
        val t = o?.optString("type", "") ?: ""
        if (t.isNotEmpty() && LABELS.containsKey(t)) return t
        return baseShift(date, crew)
    }

    fun noteFor(date: String, custom: JSONObject?): String =
        custom?.optJSONObject(date)?.optString("note", "") ?: ""

    fun label(type: String, note: String): String {
        if (type == "off" && note.contains("휴가")) return "휴가"
        return LABELS[type] ?: "휴무"
    }

    /**
     * "yyyy-MM-dd" 두 날짜의 일수 차이. 날짜 문자열을 그대로 UTC 자정으로 바꿔 계산한다
     * (기기 시간대로 Date 를 거치면 UTC 읽기에서 하루가 밀리므로 문자열에서 바로 만든다).
     */
    private fun dayDiff(date: String, base: Long): Int {
        val p = date.split("-")
        if (p.size != 3) return 0
        val y = p[0].toIntOrNull() ?: return 0
        val m = p[1].toIntOrNull() ?: return 0
        val d = p[2].toIntOrNull() ?: return 0
        return Math.round((utcDay(y, m, d) - base).toDouble() / 86_400_000.0).toInt()
    }

    private fun utcDay(y: Int, m: Int, d: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(y, m - 1, d, 0, 0, 0)
        return c.timeInMillis
    }

    fun isBlank(s: String?): Boolean = TextUtils.isEmpty(s)
}
