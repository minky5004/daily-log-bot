package com.minky.dailylogbot.anonymize;

import com.minky.dailylogbot.collect.DailyActivity;
import com.minky.dailylogbot.collect.DayWindow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnonymizerTest {

	private static final DayWindow WINDOW = DayWindow.of(LocalDate.of(2026, 8, 16));

	private static final String SECRET_REPO = "minky5004/secret-side-project";
	private static final String SECRET_MESSAGE = "feat: 사내 정산 로직";
	private static final String SECRET_FILE = "src/main/java/billing/Settlement.java";

	private static DailyActivity.Commit commit(String repo, boolean isPrivate, String at) {
		return new DailyActivity.Commit(
				repo,
				isPrivate,
				"0".repeat(40),
				Instant.parse(at),
				isPrivate ? SECRET_MESSAGE : "docs: README 갱신\n\n- 실측값 반영",
				12,
				3,
				List.of(isPrivate ? SECRET_FILE : "README.md"));
	}

	private static DailyActivity.PullRequest pull(String repo, boolean isPrivate, String at) {
		return new DailyActivity.PullRequest(
				repo, isPrivate, 7, isPrivate ? SECRET_MESSAGE : "[DOCS] 갱신", "", Instant.parse(at));
	}

	@Test
	@DisplayName("private 세부는 리포명 · 메시지 · 파일 경로 어느 것도 남지 않는다")
	void privateDetailIsGone() {
		DailyActivity activity =
				new DailyActivity(
						WINDOW,
						List.of(
								commit("minky5004/study-log", false, "2026-08-16T03:54:19Z"),
								commit(SECRET_REPO, true, "2026-08-16T06:40:13Z")),
						List.of(pull(SECRET_REPO, true, "2026-08-16T07:12:00Z")));

		AnonymousDay day = Anonymizer.strip(activity);

		String rendered = day.toString();
		assertFalse(rendered.contains(SECRET_REPO), rendered);
		assertFalse(rendered.contains(SECRET_MESSAGE), rendered);
		assertFalse(rendered.contains(SECRET_FILE), rendered);

		assertEquals(1, day.commits().size());
		assertEquals("minky5004/study-log", day.commits().get(0).repo());
		assertTrue(day.pullRequests().isEmpty());
	}

	@Test
	@DisplayName("한 리포를 커밋과 PR 양쪽에서 만나도 저장소는 1곳")
	void oneRepoCountsOnce() {
		DailyActivity activity =
				new DailyActivity(
						WINDOW,
						List.of(
								commit(SECRET_REPO, true, "2026-08-16T06:40:13Z"),
								commit(SECRET_REPO, true, "2026-08-16T06:55:02Z")),
						List.of(pull(SECRET_REPO, true, "2026-08-16T07:12:00Z")));

		AnonymousDay.Hidden hidden = Anonymizer.strip(activity).hidden();

		assertEquals(new AnonymousDay.Hidden(1, 2, 1), hidden);
		assertEquals("비공개 저장소 1곳 · 커밋 2건 · PR 1건", hidden.describe());
	}

	@Test
	@DisplayName("private 커밋만 있던 날도 올릴 것이 있다 — 시각은 익명화 대상이 아니다")
	void privateOnlyDayStillHasStart() {
		DailyActivity activity =
				new DailyActivity(
						WINDOW, List.of(commit(SECRET_REPO, true, "2026-08-16T06:40:13Z")), List.of());

		AnonymousDay day = Anonymizer.strip(activity);

		assertFalse(day.isEmpty());
		assertEquals(Instant.parse("2026-08-16T06:40:13Z"), day.firstCommitAt().orElseThrow());
		assertEquals("비공개 저장소 1곳 · 커밋 1건", day.hidden().describe());
	}

	@Test
	@DisplayName("커밋이 0건이면 올릴 것 없는 날")
	void noCommitsMeansEmptyDay() {
		DailyActivity activity =
				new DailyActivity(WINDOW, List.of(), List.of(pull("minky5004/study-log", false, "2026-08-16T07:12:00Z")));

		AnonymousDay day = Anonymizer.strip(activity);

		assertTrue(day.isEmpty());
		assertTrue(day.hidden().isEmpty());
	}

	@Test
	@DisplayName("public 커밋은 제목 · 증감 · 파일 목록이 그대로 통과한다")
	void publicDetailSurvives() {
		DailyActivity activity =
				new DailyActivity(
						WINDOW, List.of(commit("minky5004/study-log", false, "2026-08-16T03:54:19Z")), List.of());

		AnonymousDay.Commit commit = Anonymizer.strip(activity).commits().get(0);

		assertEquals("docs: README 갱신", commit.subject());
		assertEquals(12, commit.additions());
		assertEquals(List.of("README.md"), commit.files());
	}
}
