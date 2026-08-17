package com.minky.dailylogbot.collect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DayWindowTest {

	@Test
	@DisplayName("KST 하루는 전날 15:00 UTC 에서 시작하는 24시간")
	void seoulDayMapsToUtcWindow() {
		DayWindow window = DayWindow.of(LocalDate.of(2026, 8, 16));

		assertEquals(Instant.parse("2026-08-15T15:00:00Z"), window.from());
		assertEquals(Instant.parse("2026-08-16T15:00:00Z"), window.toExclusive());
		assertEquals(Duration.ofHours(24), Duration.between(window.from(), window.toExclusive()));
	}

	@Test
	@DisplayName("until 은 경계 1초 앞 — 자정 정각 커밋의 이틀 중복 방지")
	void untilStopsBeforeTheBoundary() {
		DayWindow window = DayWindow.of(LocalDate.of(2026, 8, 16));

		assertEquals("2026-08-15T15:00:00Z", window.since());
		assertEquals("2026-08-16T14:59:59Z", window.until());
	}

	@Test
	@DisplayName("KST 자정 직후 실행이 가리키는 어제")
	void yesterdayIsResolvedInSeoul() {
		Clock justAfterMidnight = Clock.fixed(Instant.parse("2026-08-16T15:00:30Z"), ZoneOffset.UTC);

		assertEquals(LocalDate.of(2026, 8, 16), DayWindow.yesterday(justAfterMidnight).date());
	}

	@Test
	@DisplayName("예약이 밀려도 대상은 흔들리지 않는다 — 같은 KST 날짜 안이면 같은 창")
	void delayedRunKeepsTheSameTarget() {
		Clock onTime = Clock.fixed(Instant.parse("2026-08-16T15:00:00Z"), ZoneOffset.UTC);
		Clock delayed = Clock.fixed(Instant.parse("2026-08-16T20:40:00Z"), ZoneOffset.UTC);

		assertEquals(DayWindow.yesterday(onTime), DayWindow.yesterday(delayed));
	}
}
