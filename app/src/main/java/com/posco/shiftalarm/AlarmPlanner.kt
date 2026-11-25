package com.posco.shiftalarm

import java.util.Locale

/**
 * 날짜별 알람 목록 계산 (v2.2).
 *
 * 안드로이드 API 를 쓰지 않는 순수 로직이라 JVM 단위 테스트(`AlarmPlannerTest`)로 검증한다.
 * 웹(index.html)의 `daySlotRows()` / `alarmsFor()` 와 같은 규칙을 따른다.
 *
 *  - 날짜 예외가 없으면 그 근무의 기본 알람 목록을 쓴다(근무 알람이 꺼져 있으면 빈 목록).
 *  - 날짜 예외가 있으면 그 날의 목록 **전체**를 예외가 대신한다.
 *    · 예외에 alarms 목록이 있으면 그 목록을 그대로(알람별 켜짐/꺼짐 포함) 쓴다.
 *    · 예전(v2.1) 형식(on, time)이면 기본 목록을 한 건으로 통째로 바꾼다 — 잔여 알람이 남지 않는다.
 *
 * v2.1 까지는 예외를 첫 번째 알람에만 적용해서, 주간에 알람이 2개(05:20·05:21)이면
 * 날짜 예외로 06:35 를 정한 날에도 05:21 이 그대로 살아 05:21 에 울렸다(2026-11-26).
 */
object AlarmPlanner {
    const val MAX_SLOTS = 6
    const val DEFAULT_TIME = "07:00"
    const val MODE_SOUND = "sound"
    const val MODE_VIBRATE = "vibrate"

    private val TIME_RE = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

    data class Slot(
        val time: String,
        val mode: String = MODE_SOUND,
        val enabled: Boolean = true
    )

    /**
     * 날짜 예외 한 건.
     * @param slots null 이면 예전 형식(on, time) — 기본 목록을 한 건으로 대체한다.
     */
    data class DayOverride(
        val on: Boolean = true,
        val time: String = "",
        val slots: List<Slot>? = null
    )

    fun modeOf(v: String?): String = if (v == MODE_VIBRATE) MODE_VIBRATE else MODE_SOUND

    /** "H:m" · "HH:mm" 을 "HH:mm" 으로. 형식이 틀리면 fallback(없으면 07:00). */
    fun normTime(t: String?, fallback: String = DEFAULT_TIME): String {
        val raw = (t ?: "").trim()
        if (TIME_RE.matches(raw)) return format(raw)
        val fb = fallback.trim()
        return if (TIME_RE.matches(fb)) format(fb) else DEFAULT_TIME
    }

    private fun format(hm: String): String {
        val m = TIME_RE.matchEntire(hm) ?: return DEFAULT_TIME
        return String.format(Locale.US, "%02d:%s", m.groupValues[1].toInt(), m.groupValues[2])
    }

    /** 시각 순으로 정렬하고 최대 개수까지 자른다(알람별 켜짐 여부는 그대로). */
    fun clean(slots: List<Slot>): List<Slot> = slots
        .map { Slot(normTime(it.time), modeOf(it.mode), it.enabled) }
        .sortedBy { it.time }
        .take(MAX_SLOTS)

    /**
     * 그 날짜에 실제로 쓸 알람 슬롯(켜짐 여부 포함, 시각 순).
     * 호출한 쪽이 `enabled` 인 슬롯만 AlarmManager 에 건다.
     */
    fun daySlots(typeOn: Boolean, base: List<Slot>, override: DayOverride?): List<Slot> {
        val cleanBase = clean(base)
        if (override == null) {
            if (!typeOn) return emptyList()
            return cleanBase
        }
        val first = cleanBase.firstOrNull() ?: Slot(DEFAULT_TIME)
        val list = override.slots
            ?.let { clean(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(Slot(normTime(override.time, first.time), first.mode, true))
        return if (override.on) list else list.map { it.copy(enabled = false) }
    }
}
