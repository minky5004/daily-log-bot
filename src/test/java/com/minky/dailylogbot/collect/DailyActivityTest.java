package com.minky.dailylogbot.collect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyActivityTest {

	private static final DayWindow WINDOW = DayWindow.of(LocalDate.of(2026, 8, 16));

	private static DailyActivity.Commit commitAt(String at) {
		return new DailyActivity.Commit(
				"minky5004/study-log", false, "0".repeat(40), Instant.parse(at), "docs: 갱신", 1, 0, List.of());
	}

	private static DailyActivity.PullRequest pull() {
		return new DailyActivity.PullRequest(
				"minky5004/study-log", false, 36, "[DOCS] 갱신", "", Instant.parse("2026-08-16T04:26:13Z"));
	}

	@Test
	@DisplayName("PR 만 있고 커밋이 0건이면 올릴 것 없는 날 — start 를 정할 근거가 없다")
	void pullRequestAloneIsNotEnough() {
		DailyActivity activity = new DailyActivity(WINDOW, List.of(), List.of(pull()));

		assertTrue(activity.isEmpty());
		assertTrue(activity.firstCommitAt().isEmpty());
	}

	@Test
	@DisplayName("커밋이 있으면 첫 커밋 시각이 start 자리")
	void firstCommitIsTheEarliest() {
		DailyActivity activity =
				new DailyActivity(
						WINDOW,
						List.of(commitAt("2026-08-16T06:40:13Z"), commitAt("2026-08-16T03:54:19Z")),
						List.of());

		assertFalse(activity.isEmpty());
		assertEquals(Instant.parse("2026-08-16T03:54:19Z"), activity.firstCommitAt().orElseThrow());
	}

	@Test
	@DisplayName("커밋 제목은 첫 줄까지")
	void subjectStopsAtTheFirstLine() {
		DailyActivity.Commit commit =
				new DailyActivity.Commit(
						"minky5004/study-log",
						false,
						"0".repeat(40),
						Instant.parse("2026-08-16T03:54:19Z"),
						"docs: README 갱신\n\n- 실측값 반영",
						1,
						0,
						List.of());

		assertEquals("docs: README 갱신", commit.subject());
	}
}
