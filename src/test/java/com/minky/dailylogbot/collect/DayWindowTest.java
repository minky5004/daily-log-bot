package com.minky.dailylogbot.collect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	@Test
	@DisplayName("지정 날짜가 없으면 어제 — 예약 발화의 기본")
	void blankTargetFallsBackToYesterday() {
		Clock clock = Clock.fixed(Instant.parse("2026-09-26T15:44:00Z"), ZoneOffset.UTC);
		assertEquals(LocalDate.of(2026, 9, 26), DayWindow.target(null, clock).date());
		assertEquals(LocalDate.of(2026, 9, 26), DayWindow.target("  ", clock).date());
	}

	@Test
	@DisplayName("지정 날짜가 있으면 그 하루 — 어제만 되던 복구의 시한이 사라지는 자리")
	void explicitTargetIsUsed() {
		Clock clock = Clock.fixed(Instant.parse("2026-09-26T15:44:00Z"), ZoneOffset.UTC);
		assertEquals(DayWindow.of(LocalDate.of(2026, 9, 25)), DayWindow.target("2026-09-25", clock));
	}

	@Test
	@DisplayName("KST 오늘 · 미래는 거부 — 덜 끝난 하루를 올리면 그날 밤 예약이 이미 있음으로 건너뛴다")
	void todayOrLaterIsRejected() {
		// 15:44 UTC 는 KST 로 이미 9/27 00:44 — 오늘은 9/27
		Clock clock = Clock.fixed(Instant.parse("2026-09-26T15:44:00Z"), ZoneOffset.UTC);
		assertThrows(IllegalArgumentException.class, () -> DayWindow.target("2026-09-27", clock));
		assertThrows(IllegalArgumentException.class, () -> DayWindow.target("2026-10-01", clock));
	}

	@Test
	@DisplayName("형식이 틀린 날짜는 입력값을 실어 거부")
	void malformedTargetIsRejected() {
		Clock clock = Clock.fixed(Instant.parse("2026-09-26T15:44:00Z"), ZoneOffset.UTC);
		IllegalArgumentException e =
				assertThrows(IllegalArgumentException.class, () -> DayWindow.target("2026-9-25", clock));
		assertTrue(e.getMessage().contains("2026-9-25"), e.getMessage());
		// 워크플로 Target date 단계와 같은 판정 — 공백이 섞인 표기도 거부
		assertThrows(IllegalArgumentException.class, () -> DayWindow.target(" 2026-09-25", clock));
	}
}
