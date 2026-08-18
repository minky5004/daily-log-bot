package com.minky.dailylogbot.summarize;

import com.minky.dailylogbot.anonymize.AnonymousDay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummarizerTest {

	private static final LocalDate DATE = LocalDate.of(2026, 8, 16);
	private static final Instant COMMITTED = Instant.parse("2026-08-16T03:54:19Z");

	private static AnonymousDay day(
			List<AnonymousDay.Commit> commits,
			List<AnonymousDay.PullRequest> pulls,
			AnonymousDay.Hidden hidden) {
		return new AnonymousDay(DATE, Optional.of(COMMITTED), commits, pulls, hidden);
	}

	private static AnonymousDay.Commit commit(List<String> files) {
		return new AnonymousDay.Commit(
				"minky5004/study-log",
				COMMITTED,
				"docs: README 갱신\n\n- 실측값 반영",
				12,
				3,
				files);
	}

	@Test
	@DisplayName("커밋 · PR · 첫 커밋 시각이 프롬프트에 그대로 실린다")
	void promptCarriesTheDay() {
		AnonymousDay day = day(
				List.of(commit(List.of("README.md"))),
				List.of(new AnonymousDay.PullRequest(
						"minky5004/study-log", 7, "[DOCS] 갱신", "## 무엇을", COMMITTED)),
				new AnonymousDay.Hidden(0, 0, 0));

		String prompt = Summarizer.prompt(day);

		assertTrue(prompt.contains("2026-08-16"), prompt);
		// UTC 03:54 는 KST 12:54 다. 모델이 보는 시각도 기록의 start 와 같은 시간대여야 한다
		assertTrue(prompt.contains("첫 커밋 12:54"), prompt);
		assertTrue(prompt.contains("docs: README 갱신"), prompt);
		assertTrue(prompt.contains("실측값 반영"), prompt);
		assertTrue(prompt.contains("README.md"), prompt);
		assertTrue(prompt.contains("minky5004/study-log#7 12:54 · [DOCS] 갱신"), prompt);
	}

	@Test
	@DisplayName("숨긴 활동은 집계 문장 한 줄로만 들어간다")
	void hiddenGoesInAsCountsOnly() {
		AnonymousDay day = day(List.of(), List.of(), new AnonymousDay.Hidden(1, 2, 0));

		assertTrue(Summarizer.prompt(day).contains("비공개 저장소 1곳 · 커밋 2건"));
	}

	@Test
	@DisplayName("숨긴 것이 없으면 비공개 절 자체가 없다")
	void noHiddenNoSection() {
		AnonymousDay day = day(
				List.of(commit(List.of("README.md"))), List.of(), new AnonymousDay.Hidden(0, 0, 0));

		// 규칙 블록에도 "비공개" 라는 낱말이 있어 집계 문장이 만드는 말로 본다
		assertFalse(Summarizer.prompt(day).contains("비공개 저장소"));
	}

	@Test
	@DisplayName("파일이 상한을 넘으면 나머지는 개수로 접힌다")
	void longFileListIsClipped() {
		List<String> files = new ArrayList<>();
		for (int i = 1; i <= 25; i++) {
			files.add("src/generated/File%02d.java".formatted(i));
		}

		String prompt = Summarizer.prompt(
				day(List.of(commit(files)), List.of(), new AnonymousDay.Hidden(0, 0, 0)));

		assertTrue(prompt.contains("File20.java"), prompt);
		assertFalse(prompt.contains("File21.java"), prompt);
		assertTrue(prompt.contains("외 5개"), prompt);
	}

	@Test
	@DisplayName("빈 요약 · 빈 본문은 기록으로 쓰지 않는다")
	void emptyPartsAreBroken() {
		assertEquals("요약이 비어 있다", Summarizer.brokenReason(new TilDraft("  ", "본문")));
		assertEquals("본문이 비어 있다", Summarizer.brokenReason(new TilDraft("요약", "")));
	}

	@Test
	@DisplayName("상한을 넘긴 요약은 잘라 담지 않고 실패한다")
	void tooLongSummaryIsBroken() {
		String reason = Summarizer.brokenReason(new TilDraft("가".repeat(501), "본문"));

		assertTrue(reason.startsWith("요약이 501자다 (상한 500자)"), reason);
	}

	@Test
	@DisplayName("상한에 걸치는 요약은 통과한다")
	void summaryAtTheLimitPasses() {
		assertNull(Summarizer.brokenReason(new TilDraft("가".repeat(500), "본문")));
	}
}
