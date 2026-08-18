package com.minky.dailylogbot.collect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyActivityTest {

	private static final DayWindow WINDOW = DayWindow.of(LocalDate.of(2026, 8, 16));

	private static DailyActivity.Commit commitAt(String at) {
		return new DailyActivity.Commit(
				"minky5004/study-log", false, "0".repeat(40), Instant.parse(at), "docs: 갱신", 1, 0, List.of());
	}

	@Test
	@DisplayName("커밋이 있으면 첫 커밋 시각이 start 자리")
	void firstCommitIsTheEarliest() {
		DailyActivity activity =
				new DailyActivity(
						WINDOW,
						List.of(commitAt("2026-08-16T06:40:13Z"), commitAt("2026-08-16T03:54:19Z")),
						List.of());

		assertEquals(Instant.parse("2026-08-16T03:54:19Z"), activity.firstCommitAt().orElseThrow());
	}
}
