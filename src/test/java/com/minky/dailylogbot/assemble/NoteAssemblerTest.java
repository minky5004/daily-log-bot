package com.minky.dailylogbot.assemble;

import com.minky.dailylogbot.anonymize.AnonymousDay;
import com.minky.dailylogbot.collect.DayWindow;
import com.minky.dailylogbot.summarize.TilDraft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteAssemblerTest {

	private static final LocalDate DATE = LocalDate.of(2026, 8, 16);

	/** 09:12:45 KST. 초가 붙어 있는 것은 분 단위 절삭까지 보기 위해서다. */
	private static final Instant FIRST = Instant.parse("2026-08-16T00:12:45Z");

	private static final TilDraft DRAFT = new TilDraft("하루를 가리키는 한 줄", "## study-log\n- 문서 갱신");

	private static AnonymousDay day(
			Instant first, List<AnonymousDay.Commit> commits, List<AnonymousDay.PullRequest> pulls) {
		return new AnonymousDay(
				DATE,
				first == null ? List.of() : List.of(first),
				commits,
				pulls,
				new AnonymousDay.Hidden(0, 0, 0));
	}

	private static AnonymousDay.Commit commit(String repo) {
		return new AnonymousDay.Commit(repo, FIRST, "docs: README 갱신", 12, 3, List.of("README.md"));
	}

	private static AnonymousDay.PullRequest pull(String repo) {
		return new AnonymousDay.PullRequest(repo, 7, "[DOCS] 갱신", "본문", FIRST);
	}

	private static AnonymousDay oneCommit() {
		return day(FIRST, List.of(commit("minky5004/study-log")), List.of());
	}

	private static Instant kst(String hhmm) {
		return DATE.atTime(LocalTime.parse(hhmm)).atZone(DayWindow.SEOUL).toInstant();
	}

	private static AnonymousDay dayOf(List<Instant> times) {
		return new AnonymousDay(
				DATE,
				times,
				List.of(commit("minky5004/study-log")),
				List.of(),
				new AnonymousDay.Hidden(0, 0, 0));
	}

	private static String markdown(AnonymousDay day, TilDraft draft) {
		return NoteAssembler.assemble(day, draft).markdown();
	}

	@Test
	@DisplayName("프론트매터는 study-log 가 읽는 키만 담는다")
	void frontMatter() {
		String md = markdown(oneCommit(), DRAFT);

		assertEquals(
				"""
				---
				title: "2026-08-16 개발 기록"
				date: 2026-08-16
				start: "09:12"
				end: "09:13"
				category: "개발"
				tags: ["study-log"]
				summary: "하루를 가리키는 한 줄"
				---
				## study-log
				- 문서 갱신
				""",
				md);
	}

	@Test
	@DisplayName("end 는 start 에 세션 합을 더한 시각 — 자리에 없던 시간은 빠진다")
	void endAddsWorkedMinutesOnly() {
		// 커밋 8건. 09:58 과 13:19 사이 201분은 앉아 있던 시간이 아니라 합이 98 + 2 = 100분
		String md = markdown(
				dayOf(List.of(
						kst("08:20"), kst("08:24"), kst("08:43"), kst("08:51"),
						kst("08:59"), kst("09:58"), kst("13:19"), kst("13:21"))),
				DRAFT);

		assertTrue(md.contains("start: \"08:20\""));
		assertTrue(md.contains("end: \"10:00\""));
	}

	@Test
	@DisplayName("durationMinutes 는 넣지 않는다 — 가져오기가 읽지 않는 키다")
	void noDuration() {
		assertFalse(markdown(oneCommit(), DRAFT).contains("durationMinutes"));
	}

	@Test
	@DisplayName("제목과 날짜는 중복 판정 키라 본문 밖에서도 꺼내 쓸 수 있다")
	void identity() {
		TilNote note = NoteAssembler.assemble(oneCommit(), DRAFT);

		assertEquals("2026-08-16 개발 기록", note.title());
		assertEquals(DATE, note.date());
	}

	@Test
	@DisplayName("23:59 에 시작한 날의 end 는 00:00 으로 넘어간다")
	void pastMidnight() {
		AnonymousDay day = day(
				Instant.parse("2026-08-16T14:59:10Z"),
				List.of(commit("minky5004/study-log")),
				List.of());

		String md = markdown(day, DRAFT);

		assertTrue(md.contains("start: \"23:59\""), md);
		assertTrue(md.contains("end: \"00:00\""), md);
	}

	@Test
	@DisplayName("태그는 소유자를 뗀 public 리포 이름 · 중복은 한 번")
	void tags() {
		AnonymousDay day = day(
				FIRST,
				List.of(commit("minky5004/study-log"), commit("minky5004/study-log")),
				List.of(pull("minky5004/daily-log-bot")));

		assertTrue(markdown(day, DRAFT).contains("tags: [\"study-log\", \"daily-log-bot\"]"));
	}

	@Test
	@DisplayName("50자를 넘는 리포명은 태그에서 뺀다 — 노트 한 장이 거부되는 자리")
	void longRepoName() {
		String tooLong = "a".repeat(51);
		AnonymousDay day = day(
				FIRST,
				List.of(commit("minky5004/" + tooLong), commit("minky5004/study-log")),
				List.of());

		assertTrue(markdown(day, DRAFT).contains("tags: [\"study-log\"]"));
	}

	@Test
	@DisplayName("public 활동이 없던 날의 태그는 빈 배열")
	void noTags() {
		AnonymousDay day = new AnonymousDay(
				DATE, List.of(FIRST), List.of(), List.of(), new AnonymousDay.Hidden(1, 2, 0));

		assertTrue(markdown(day, DRAFT).contains("tags: []"));
	}

	@Test
	@DisplayName("따옴표와 역슬래시는 이스케이프한다")
	void escaping() {
		TilDraft draft = new TilDraft("\"인용\" 과 역슬래시 \\ 를 담은 요약", "본문");

		assertTrue(
				markdown(oneCommit(), draft)
						.contains("summary: \"\\\"인용\\\" 과 역슬래시 \\\\ 를 담은 요약\""));
	}

	@Test
	@DisplayName("요약의 줄바꿈은 한 줄로 편다 — 뒷줄이 키로 읽히는 자리다")
	void oneLineSummary() {
		TilDraft draft = new TilDraft("앞 문장\nend: \"23:59\"", "본문");

		String md = markdown(oneCommit(), draft);

		assertTrue(md.contains("summary: \"앞 문장 end: \\\"23:59\\\"\""), md);
		assertEquals(1, md.lines().filter(line -> line.startsWith("end: ")).count(), md);
	}

	@Test
	@DisplayName("첫 커밋이 없는 날은 접지 않는다")
	void noFirstCommit() {
		AnonymousDay day = day(null, List.of(), List.of());

		assertThrows(IllegalStateException.class, () -> NoteAssembler.assemble(day, DRAFT));
	}
}
