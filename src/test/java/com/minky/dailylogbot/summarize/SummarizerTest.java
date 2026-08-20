package com.minky.dailylogbot.summarize;

import com.minky.dailylogbot.anonymize.AnonymousDay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummarizerTest {

	private static final LocalDate DATE = LocalDate.of(2026, 8, 16);
	private static final Instant COMMITTED = Instant.parse("2026-08-16T03:54:19Z");

	private static AnonymousDay day(
			List<AnonymousDay.Commit> commits,
			List<AnonymousDay.PullRequest> pulls,
			AnonymousDay.Hidden hidden) {
		return new AnonymousDay(DATE, List.of(COMMITTED), commits, pulls, hidden);
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

	private static AnonymousDay.PullRequest pull(String body) {
		return new AnonymousDay.PullRequest("minky5004/study-log", 7, "[DOCS] 갱신", body, COMMITTED);
	}

	private static AnonymousDay onlyPublicCommit() {
		return day(List.of(commit(List.of("README.md"))), List.of(), new AnonymousDay.Hidden(0, 0, 0));
	}

	@Test
	@DisplayName("커밋 · PR · 첫 커밋 시각이 프롬프트에 그대로 실린다")
	void promptCarriesTheDay() {
		AnonymousDay day = day(
				List.of(commit(List.of("README.md"))),
				List.of(pull("## 무엇을")),
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
		// 규칙 블록에도 "비공개" 라는 낱말이 있어 집계 문장이 만드는 말로 본다
		assertFalse(Summarizer.prompt(onlyPublicCommit()).contains("비공개 저장소"));
	}

	@Test
	@DisplayName("공개 활동이 없는 날은 저장소 절을 만들지 말라고 일러 준다")
	void privateOnlyDayTellsTheModelSo() {
		AnonymousDay day = day(List.of(), List.of(), new AnonymousDay.Hidden(1, 2, 0));

		// 저장소 이름이 한 번도 나오지 않는 프롬프트에 "저장소마다 절" 규칙만 남으면 모델이
		// 이름을 지어낸다. 지어낸 이름은 형태가 멀쩡해 brokenReason 도 통과한다
		assertTrue(Summarizer.prompt(day).contains("저장소 절을 만들지 말고"));
		assertFalse(Summarizer.prompt(onlyPublicCommit()).contains("저장소 절을 만들지 말고"));
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
	@DisplayName("커밋 메시지도 PR 본문과 같은 상한을 받는다")
	void longCommitMessageIsClipped() {
		AnonymousDay.Commit fat = new AnonymousDay.Commit(
				"minky5004/study-log", COMMITTED, "머리\n" + "가".repeat(600), 1, 0, List.of("A.java"));

		String prompt = Summarizer.prompt(
				day(List.of(fat), List.of(), new AnonymousDay.Hidden(0, 0, 0)));

		assertTrue(prompt.contains("머리"), prompt);
		assertFalse(prompt.contains("가".repeat(600)), prompt);
	}

	@Test
	@DisplayName("상한 경계에 이모지가 걸려도 반쪽짜리 문자를 남기지 않는다")
	void clipKeepsSurrogatePairsWhole() {
		String emoji = new String(Character.toChars(0x1F642));
		// 이모지가 499~500번째 코드 유닛에 걸치게 만든다
		String body = "가".repeat(499) + emoji + "나".repeat(10);

		String prompt = Summarizer.prompt(
				day(List.of(), List.of(pull(body)), new AnonymousDay.Hidden(0, 0, 0)));

		assertFalse(prompt.contains(String.valueOf(emoji.charAt(0))), "짝 잃은 대리 문자가 남았다");
	}

	@Test
	@DisplayName("응답의 요약과 본문이 초안 두 칸으로 들어간다")
	void draftComesFromTheResponse() {
		SummaryModel model = (prompt, schema) ->
				"{\"summary\": \"하루 한 줄\", \"body\": \"## minky5004/study-log — 갱신\"}";

		TilDraft draft = new Summarizer(model).summarize(onlyPublicCommit());

		assertEquals("하루 한 줄", draft.summary());
		assertTrue(draft.body().startsWith("## minky5004/study-log"), draft.body());
	}

	@Test
	@DisplayName("쓸 수 없는 응답은 초안이 되지 못하고 실행을 끝낸다")
	void brokenResponseStopsTheRun() {
		SummaryModel model = (prompt, schema) -> "{\"summary\": \"\", \"body\": \"본문\"}";

		IllegalStateException thrown = assertThrows(
				IllegalStateException.class, () -> new Summarizer(model).summarize(onlyPublicCommit()));

		assertTrue(thrown.getMessage().contains("요약이 비어 있다"), thrown.getMessage());
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
