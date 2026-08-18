package com.minky.dailylogbot.upload;

import com.minky.dailylogbot.assemble.TilNote;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 하루 한 장을 study-log 로 올리되, 이미 있는 날은 건너뛴다.
 *
 * <p>중복 판정을 인증한 상태의 {@code existsBy...} 에 기대지 않는 것은, 사용자가 아침에
 * {@code end} 를 실제 종료 시각으로 고치는 순간 그 키(날짜·제목·시작·종료)가 어긋나 재실행이
 * 기록을 하나 더 만들기 때문이다. 그래서 시각을 빼고 <b>공개 목록</b>에서 그날 그 제목의
 * 유무만 본다 — 인증도 상태 파일도 필요 없고, 사용자가 시각을 고쳐도 흔들리지 않는다.
 */
public final class Uploader {

	/** 목록 응답에서 세션 한 줄의 유무. 설계 7절이 「선택자에 묶이는 유일한 지점」이라 부른 곳이다. */
	private static final Pattern SESSION =
			Pattern.compile("<li[^>]*\\bclass=\"[^\"]*\\bsession\\b[^\"]*\"");

	/** 결과 표의 추가 · 건너뜀 · 실패 세 값이 이 순서로 나온다. */
	private static final Pattern TALLY = Pattern.compile("class=\"tally-value\">(\\d+)<");

	/** 실패 표의 칸. 사유가 있어야 무엇을 고칠지 알 수 있다 — 건수만으로는 다시 올릴 수 없다. */
	private static final Pattern CELL = Pattern.compile("<td>([^<]*)</td>");

	private final StudyLog studyLog;

	public Uploader(StudyLog studyLog) {
		this.studyLog = studyLog;
	}

	/**
	 * 올렸으면 {@code true}, 이미 있어 건너뛰었으면 {@code false}. 업로드가 실패로 끝나면
	 * 예외를 던져 워크플로를 실패로 세운다 — 설계 4절의 「실패는 워크플로를 실패로」 그대로다.
	 */
	public boolean publish(TilNote note) {
		String listing = studyLog.search(note.date(), note.date(), note.title());
		if (SESSION.matcher(listing).find()) {
			return false;
		}

		studyLog.login();
		verify(studyLog.importMarkdown(note.date() + ".md", note.markdown()));
		return true;
	}

	/**
	 * 결과 표를 읽어 실패면 던진다.
	 *
	 * <p>추가·건너뜀이 모두 0 이면 올렸는데 아무것도 안 들어간 것이라 성공이 아니다. 실패
	 * 건수가 있으면 표의 사유를 메시지에 실어, 공개 실행 로그만 보고도 무엇이 틀렸는지 안다.
	 */
	private static void verify(String report) {
		Matcher tally = TALLY.matcher(report);
		int[] counts = new int[3];
		int seen = 0;
		while (seen < 3 && tally.find()) {
			counts[seen++] = Integer.parseInt(tally.group(1));
		}
		if (seen < 3) {
			throw new IllegalStateException("study-log 업로드 결과를 읽지 못함 — " + report);
		}

		int added = counts[0];
		int skipped = counts[1];
		int failed = counts[2];
		if (failed > 0) {
			throw new IllegalStateException("study-log 업로드 실패 — " + reasons(report));
		}
		if (added == 0 && skipped == 0) {
			throw new IllegalStateException("study-log 에 아무것도 들어가지 않음 — " + report);
		}
	}

	private static String reasons(String report) {
		int table = report.indexOf("class=\"failures\"");
		Matcher cell = CELL.matcher(table < 0 ? report : report.substring(table));
		StringBuilder sb = new StringBuilder();
		while (cell.find()) {
			sb.append(sb.isEmpty() ? "" : " · ").append(cell.group(1).strip());
		}
		return sb.isEmpty() ? report : sb.toString();
	}
}
