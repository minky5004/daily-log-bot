package com.minky.dailylogbot.upload;

import java.time.LocalDate;

/**
 * study-log 로 드나드는 통로. 무엇을 조회하고 언제 건너뛸지는 {@link Uploader} 몫이고,
 * 여기는 폼 로그인 · CSRF · multipart 같은 전송 기계만 진다 — 수집 쪽의
 * {@code GitHubClient}/{@code ActivityCollector} 가 나뉜 것과 같은 선이다.
 */
public interface StudyLog {

	/**
	 * 공개 목록 조회. 로그인 없이 그날 그 제목의 기록이 이미 있는지 보려는 자리다 —
	 * 응답은 HTML 그대로고, {@code li.session} 유무 판정은 부르는 쪽이 한다.
	 */
	String search(LocalDate from, LocalDate to, String keyword);

	/** 폼 로그인. 세션 쿠키를 통로가 보관한다. 실패는 예외로 끝낸다. */
	void login();

	/**
	 * {@code POST /import} 로 마크다운 한 장을 올린다. 파일명은 {@code .md} 로 끝나야 하고
	 * (아니면 서버가 ZIP 으로 읽는다) 결과 표 HTML 을 그대로 돌려준다.
	 */
	String importMarkdown(String fileName, String markdown);
}
