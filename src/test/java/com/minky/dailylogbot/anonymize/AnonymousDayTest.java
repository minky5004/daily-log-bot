package com.minky.dailylogbot.anonymize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnonymousDayTest {

	private static final LocalDate DATE = LocalDate.of(2026, 8, 16);

	private static AnonymousDay day(List<Instant> times) {
		return new AnonymousDay(DATE, times, List.of(), List.of(), new AnonymousDay.Hidden(0, 0, 0));
	}

	@Test
	@DisplayName("커밋 시각은 받은 순서와 무관하게 시간순 — start 와 세션 계산이 그 순서에 기댄다")
	void commitTimesAreSorted() {
		AnonymousDay day = day(List.of(
				Instant.parse("2026-08-16T07:00:00Z"),
				Instant.parse("2026-08-16T05:00:00Z"),
				Instant.parse("2026-08-16T06:00:00Z")));

		assertEquals(Instant.parse("2026-08-16T05:00:00Z"), day.firstCommitAt().orElseThrow());
		assertEquals(
				List.of(
						Instant.parse("2026-08-16T05:00:00Z"),
						Instant.parse("2026-08-16T06:00:00Z"),
						Instant.parse("2026-08-16T07:00:00Z")),
				day.commitTimes());
	}

	@Test
	@DisplayName("넘겨받은 목록을 그대로 쥐지 않는다 — 부르는 쪽이 뒤에 고쳐도 기록은 그대로")
	void commitTimesAreCopied() {
		List<Instant> mutable = new ArrayList<>(List.of(Instant.parse("2026-08-16T05:00:00Z")));

		AnonymousDay day = day(mutable);
		mutable.add(Instant.parse("2026-08-16T09:00:00Z"));

		assertEquals(1, day.commitTimes().size());
		assertThrows(
				UnsupportedOperationException.class,
				() -> day.commitTimes().add(Instant.parse("2026-08-16T10:00:00Z")));
	}
}
