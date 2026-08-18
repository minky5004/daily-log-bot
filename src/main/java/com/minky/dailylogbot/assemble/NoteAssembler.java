package com.minky.dailylogbot.assemble;

import com.minky.dailylogbot.anonymize.AnonymousDay;
import com.minky.dailylogbot.collect.DayWindow;
import com.minky.dailylogbot.summarize.TilDraft;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.stream.Collectors;

/**
 * 요약 초안과 하루의 시각을 study-log 가 읽는 마크다운 한 장으로 접는다.
 *
 * <p>키 이름과 따옴표는 취향이 아니라 계약이다 — 상대는 study-log 의 {@code FrontMatterParser}
 * 이고 이 리포는 그쪽을 고치지 않는다. {@code durationMinutes} 를 넣지 않는 것도 같은 이유다.
 * 내보내기는 그 키를 쓰지만 가져오기는 읽지 않고, 도메인이 {@code start} · {@code end} 에서
 * 파생시킨다.
 */
public final class NoteAssembler {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	/** 설계 4절 — study-log 에 분야 관리 화면이 없어 한번 생긴 분야를 지울 수단이 없다. */
	private static final String CATEGORY = "개발";

	private static final String DELIMITER = "---\n";

	private NoteAssembler() {}

	public static TilNote assemble(AnonymousDay day, TilDraft draft) {
		// 부르는 쪽이 이미 커밋 0건인 날을 끊지만, 없는 시각을 지어내느니 여기서도 끝낸다.
		// start 는 실제로 있었던 시각이라는 것이 이 기록의 유일한 근거다
		Instant first = day.firstCommitAt().orElseThrow(
				() -> new IllegalStateException("첫 커밋이 없는 날은 접을 수 없다 — " + day.date()));

		LocalTime start = LocalTime.ofInstant(first, DayWindow.SEOUL).truncatedTo(ChronoUnit.MINUTES);
		String title = "%s 개발 기록".formatted(day.date());

		StringBuilder md = new StringBuilder(DELIMITER);
		quoted(md, "title", title);
		md.append("date: ").append(day.date()).append('\n');
		quoted(md, "start", TIME.format(start));
		// 설계 4절의 duration 1분. 23:59 에 시작한 날은 00:00 으로 넘어가고, study-log 가
		// 뒤선 종료를 익일로 읽어 그대로 1분이 된다
		quoted(md, "end", TIME.format(start.plusMinutes(1)));
		quoted(md, "category", CATEGORY);
		md.append("tags: ").append(tagArray(tags(day))).append('\n');
		quoted(md, "summary", oneLine(draft.summary()));
		md.append(DELIMITER).append(draft.body()).append('\n');

		return new TilNote(title, day.date(), md.toString());
	}

	/**
	 * 태그는 그날 손댄 public 리포 이름들. 소유자 접두는 뗀다 — 계정 하나만 보는 도구라
	 * {@code minky5004/} 가 모든 태그에 붙으면 태그로 갈리는 것이 하나도 없다.
	 *
	 * <p>커밋 없이 PR 만 연 리포도 넣는다. 그날 한 일에는 있는데 태그에는 없는 이름이 생기면
	 * 태그로 되짚는 길이 끊긴다.
	 */
	private static List<String> tags(AnonymousDay day) {
		SequencedSet<String> names = new LinkedHashSet<>();
		day.commits().forEach(commit -> names.add(shortName(commit.repo())));
		day.pullRequests().forEach(pull -> names.add(shortName(pull.repo())));
		return List.copyOf(names);
	}

	private static String shortName(String fullName) {
		return fullName.substring(fullName.indexOf('/') + 1);
	}

	private static String tagArray(List<String> tags) {
		return tags.stream()
				.map(tag -> '"' + escape(tag) + '"')
				.collect(Collectors.joining(", ", "[", "]"));
	}

	/**
	 * 요약을 한 줄로 편다.
	 *
	 * <p>따옴표를 이스케이프해도 줄바꿈은 막지 못한다 — 프론트매터는 줄 단위로 읽혀서, 요약이
	 * 두 줄이면 뒷줄이 값이 아니라 키로 읽힌다. 모델이 두 문장을 개행으로 나눈 날
	 * {@code end: ...} 같은 줄이 섞이면 기록의 시각 자체가 바뀐다.
	 */
	private static String oneLine(String text) {
		return text.replaceAll("\\s+", " ").strip();
	}

	private static void quoted(StringBuilder out, String key, String value) {
		out.append(key).append(": \"").append(escape(value)).append("\"\n");
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
