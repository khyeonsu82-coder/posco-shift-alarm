package com.posco.shiftalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 날짜 예외(v2.2) 로직 단위 테스트 — 2026-11-26 05:21 오발화의 재발 방지.
 *
 * 실제 증상: 주간 기본 알람이 2개(05:20 · 05:21)인데, 11/26 날짜 예외로 06:35 을 저장했더니
 * 예외가 첫 알람에만 적용돼 05:21 이 남았고, 그날 05:21 에 알람이 울렸다(앱에는 "06:35 외 1개").
 */
class AlarmPlannerTest {

    private val dayBase = listOf(
        AlarmPlanner.Slot("05:20", "sound", true),
        AlarmPlanner.Slot("05:21", "vibrate", true)
    )

    @Test
    fun `예외가 없으면 기본 목록을 그대로 쓴다`() {
        val slots = AlarmPlanner.daySlots(typeOn = true, base = dayBase, override = null)
        assertEquals(listOf("05:20", "05:21"), slots.map { it.time })
        assertTrue(slots.all { it.enabled })
    }

    @Test
    fun `근무 알람이 꺼져 있고 예외도 없으면 빈 목록`() {
        assertTrue(AlarmPlanner.daySlots(typeOn = false, base = dayBase, override = null).isEmpty())
    }

    /** 11/26 버그 — 예전 형식(on + time) 예외는 그 날 목록을 한 건으로 통째로 바꾼다. */
    @Test
    fun `예전 형식 예외는 기본 알람을 모두 대체한다`() {
        val slots = AlarmPlanner.daySlots(
            typeOn = true, base = dayBase,
            override = AlarmPlanner.DayOverride(on = true, time = "06:35", slots = null)
        )
        assertEquals(1, slots.size)
        assertEquals("06:35", slots[0].time)
        assertEquals("sound", slots[0].mode)   // 첫 알람의 울림 방식을 물려받는다
        assertTrue(slots[0].enabled)
    }

    @Test
    fun `새 형식 예외는 목록 전체를 그대로 쓴다`() {
        val slots = AlarmPlanner.daySlots(
            typeOn = true, base = dayBase,
            override = AlarmPlanner.DayOverride(
                on = true,
                slots = listOf(AlarmPlanner.Slot("06:35", "sound", true))
            )
        )
        assertEquals(listOf("06:35"), slots.map { it.time })
    }

    @Test
    fun `새 형식 예외에서 껴 둔 알람은 꺼진 채로 남는다`() {
        val slots = AlarmPlanner.daySlots(
            typeOn = true, base = dayBase,
            override = AlarmPlanner.DayOverride(
                on = true,
                slots = listOf(
                    AlarmPlanner.Slot("06:35", "sound", true),
                    AlarmPlanner.Slot("05:21", "sound", false)
                )
            )
        )
        assertEquals(listOf("05:21", "06:35"), slots.map { it.time })
        assertEquals(listOf(false, true), slots.map { it.enabled })
    }

    @Test
    fun `예외로 그 날 알람을 끄면 모두 꺼진다`() {
        val slots = AlarmPlanner.daySlots(
            typeOn = true, base = dayBase,
            override = AlarmPlanner.DayOverride(on = false, time = "06:35", slots = null)
        )
        assertEquals(1, slots.size)
        assertTrue(slots.none { it.enabled })
    }

    @Test
    fun `휴무(기본 꺼짐)에 예외로 알람을 켤 수 있다`() {
        val offBase = listOf(AlarmPlanner.Slot("08:00", "sound", true))
        val slots = AlarmPlanner.daySlots(
            typeOn = false, base = offBase,
            override = AlarmPlanner.DayOverride(on = true, time = "", slots = null)
        )
        assertEquals(listOf("08:00"), slots.map { it.time })
        assertTrue(slots[0].enabled)
    }

    @Test
    fun `시각은 시각 순으로 정렬되고 6개까지만 쓴다`() {
        val many = (0 until 9).map { AlarmPlanner.Slot("0${8 - it}:00", "sound", true) }
        val slots = AlarmPlanner.clean(many)
        assertEquals(6, slots.size)
        assertEquals("00:00", slots.first().time)
        assertEquals("05:00", slots.last().time)
    }

    @Test
    fun `시각 형식 보정`() {
        assertEquals("05:05", AlarmPlanner.normTime("5:05"))
        assertEquals("23:59", AlarmPlanner.normTime("23:59"))
        assertEquals("06:30", AlarmPlanner.normTime("", "06:30"))
        assertEquals(AlarmPlanner.DEFAULT_TIME, AlarmPlanner.normTime("25:99"))
    }
}
