package com.minky.dailylogbot.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minky.dailylogbot.assemble.TilNote;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UploaderTest {

	private static final LocalDate DATE = LocalDate.of(2026, 8, 18);
	private static final TilNote NOTE =
			new TilNote("2026-08-18 개발 기록", DATE, "---\ntitle: \"2026-08-18 개발 기록\"\n---\n오늘 한 것\n");

	/** 그날 그 제목의 세션이 이미 목록에 보이면 로그인도 업로드도 하지 않는다. */
	@Test
	void 이미_있는_날은_문을_두드리지_않는다() {
		Fake fake = new Fake(SESSION_LISTING, ADDED_REPORT);

		boolean uploaded = new Uploader(fake).publish(NOTE);

		assertFalse(uploaded);
		assertFalse(fake.loggedIn);
		assertFalse(fake.imported);
	}

	/** 조회는 하루 폭(from=to=그날)에 제목을 키워드로 싣는다. */
	@Test
	void 조회는_그날_하루에_제목을_싣는다() {
		Fake fake = new Fake(EMPTY_LISTING, ADDED_REPORT);

		new Uploader(fake).publish(NOTE);

		assertEquals(List.of(DATE + "|" + DATE + "|2026-08-18 개발 기록"), fake.searches);
	}

	/** 없으면 로그인 한 번 뒤 그 마크다운을 올린다. 파일명은 날짜 · `.md` 로 끝난다. */
	@Test
	void 없으면_로그인하고_올린다() {
		Fake fake = new Fake(EMPTY_LISTING, ADDED_REPORT);

		boolean uploaded = new Uploader(fake).publish(NOTE);

		assertTrue(uploaded);
		assertTrue(fake.loggedIn);
		assertEquals("2026-08-18.md", fake.fileName);
		assertEquals(NOTE.markdown(), fake.sentMarkdown);
	}

	/** 결과 표에 실패가 있으면 워크플로를 실패로 끝낸다 — 사유를 메시지에 싣는다. */
	@Test
	void 업로드_실패는_예외로_끝낸다() {
		Fake fake = new Fake(EMPTY_LISTING, FAILED_REPORT);

		IllegalStateException e =
				assertThrows(IllegalStateException.class, () -> new Uploader(fake).publish(NOTE));
		assertTrue(e.getMessage().contains("프론트매터 없음"), e.getMessage());
	}

	/** 읽을 마크다운이 없었다는 빈 결과도 실패다 — 올렸는데 아무것도 안 들어간 것이다. */
	@Test
	void 아무것도_안_들어가면_예외() {
		Fake fake = new Fake(EMPTY_LISTING, EMPTY_REPORT);

		assertThrows(IllegalStateException.class, () -> new Uploader(fake).publish(NOTE));
	}

	/** 조회와 업로드 사이에 끼어든 기록을 서버가 건너뛰면 올린 것이 아니다 — 문은 두드렸어도. */
	@Test
	void 서버가_건너뛰면_올린_것이_아니다() {
		Fake fake = new Fake(EMPTY_LISTING, SKIPPED_REPORT);

		boolean uploaded = new Uploader(fake).publish(NOTE);

		assertFalse(uploaded);
		assertTrue(fake.loggedIn);
		assertTrue(fake.imported);
	}

	/** 값을 자리로 읽으면 놓친다 — 실패가 표의 앞에 와도 라벨로 짚어 실패로 끝낸다. */
	@Test
	void 실패가_표의_앞에_와도_실패로_읽는다() {
		Fake fake = new Fake(EMPTY_LISTING, REORDERED_FAIL_REPORT);

		assertThrows(IllegalStateException.class, () -> new Uploader(fake).publish(NOTE));
	}

	/** session- 로 시작하는 뒷날의 클래스는 세션 한 줄이 아니다 — 제 업로드를 건너뛰지 않는다. */
	@Test
	void 세션_접두_클래스는_세션이_아니다() {
		Fake fake = new Fake(SESSION_PREFIX_LISTING, ADDED_REPORT);

		boolean uploaded = new Uploader(fake).publish(NOTE);

		assertTrue(uploaded);
		assertTrue(fake.loggedIn);
	}

	private static final String SESSION_LISTING = """
			<ol class="day-list"><li class="day"><ol class="session-list">
			<li class="session"><a href="/logs/7">2026-08-18 개발 기록</a></li>
			</ol></li></ol>""";

	private static final String EMPTY_LISTING =
			"""
			<p class="empty">조건에 맞는 기록이 없습니다. 조건을 넓히거나 초기화해 보세요.</p>""";

	/** 세션 목록 자리에 session- 접두 클래스만 있는 목록 — 세션 한 줄은 없다. */
	private static final String SESSION_PREFIX_LISTING = """
			<ol class="day-list"><li class="day"><ol class="session-list">
			<li class="session-note">지난 메모</li>
			</ol></li></ol>""";

	private static final String ADDED_REPORT = """
			<ul class="tally">
			<li><span class="tally-label">추가</span><span class="tally-value">1</span></li>
			<li><span class="tally-label">건너뜀</span><span class="tally-value">0</span></li>
			<li><span class="tally-label">실패</span><span class="tally-value">0</span></li>
			</ul>""";

	private static final String FAILED_REPORT = """
			<ul class="tally">
			<li><span class="tally-label">추가</span><span class="tally-value">0</span></li>
			<li><span class="tally-label">건너뜀</span><span class="tally-value">0</span></li>
			<li><span class="tally-label">실패</span><span class="tally-value">1</span></li>
			</ul>
			<table class="failures"><tbody>
			<tr><td>2026-08-18.md</td><td>프론트매터 없음</td></tr>
			</tbody></table>""";

	private static final String SKIPPED_REPORT = """
			<ul class="tally">
			<li><span class="tally-label">추가</span><span class="tally-value">0</span></li>
			<li><span class="tally-label">건너뜀</span><span class="tally-value">1</span></li>
			<li><span class="tally-label">실패</span><span class="tally-value">0</span></li>
			</ul>""";

	/** 실패가 표의 첫 값으로 오는 배치 — 자리로 읽으면 추가로 오독한다. */
	private static final String REORDERED_FAIL_REPORT = """
			<ul class="tally">
			<li><span class="tally-label">실패</span><span class="tally-value">1</span></li>
			<li><span class="tally-label">추가</span><span class="tally-value">0</span></li>
			<li><span class="tally-label">건너뜀</span><span class="tally-value">0</span></li>
			</ul>
			<table class="failures"><tbody>
			<tr><td>2026-08-18.md</td><td>프론트매터 없음</td></tr>
			</tbody></table>""";

	private static final String EMPTY_REPORT =
			"""
			<p class="empty">읽을 마크다운이 없었습니다.</p>""";

	/** 전송을 대신 기억하는 통로. 실제 HTTP 없이 분기와 전달값만 본다. */
	private static final class Fake implements StudyLog {
		private final String listing;
		private final String report;
		private final List<String> searches = new ArrayList<>();
		private boolean loggedIn;
		private boolean imported;
		private String fileName;
		private String sentMarkdown;

		Fake(String listing, String report) {
			this.listing = listing;
			this.report = report;
		}

		@Override
		public String search(LocalDate from, LocalDate to, String keyword) {
			searches.add(from + "|" + to + "|" + keyword);
			return listing;
		}

		@Override
		public void login() {
			loggedIn = true;
		}

		@Override
		public String importMarkdown(String fileName, String markdown) {
			this.imported = true;
			this.fileName = fileName;
			this.sentMarkdown = markdown;
			return report;
		}
	}
}
