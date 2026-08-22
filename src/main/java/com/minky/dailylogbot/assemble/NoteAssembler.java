package com.minky.dailylogbot.assemble;

import com.minky.dailylogbot.anonymize.AnonymousDay;
import com.minky.dailylogbot.collect.DayWindow;
import com.minky.dailylogbot.summarize.TilDraft;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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

	/** study-log 의 태그 컬럼 길이. 넘긴 태그 하나가 노트 한 장을 통째로 거부시킨다. */
	private static final int MAX_TAG = 50;

	private NoteAssembler() {}

	public static TilNote assemble(AnonymousDay day, TilDraft draft) {
		// 부르는 쪽이 이미 커밋 0건인 날을 끊지만, 없는 시각을 지어내느니 여기서도 끝낸다.
		// start 는 실제로 있었던 시각이라는 것이 이 기록의 유일한 근거다
		Instant first = day.firstCommitAt().orElseThrow(
				() -> new IllegalStateException("첫 커밋이 없는 날은 접을 수 없다 — " + day.date()));

		LocalTime start = LocalTime.ofInstant(first, DayWindow.SEOUL).truncatedTo(ChronoUnit.MINUTES);
		// 세션이 전부 커밋 하나씩이면 합이 0분이다 — 커밋 1건인 날도, 종일 띄엄띄엄 하나씩
		// 남긴 날도 여기 온다. study-log 가 start == end 를 거부해 그 하루가 통째로 반려되므로
		// 1분으로 올린다 — 지어낸 값이 아니라 「실측 0」 의 표기다
		int worked = Math.max(Sessions.workedMinutes(day.commitTimes()), 1);
		String title = "%s 개발 기록".formatted(day.date());

		StringBuilder md = new StringBuilder(DELIMITER);
		quoted(md, "title", title);
		md.append("date: ").append(day.date()).append('\n');
		quoted(md, "start", TIME.format(start));
		// 자정을 넘긴 종료는 study-log 가 익일로 읽어 그대로 duration 이 된다
		quoted(md, "end", TIME.format(start.plusMinutes(worked)));
		quoted(md, "category", CATEGORY);
		md.append("tags: ").append(tagArray(tags(day))).append('\n');
		quoted(md, "summary", oneLine(draft.summary()));
		md.append(DELIMITER).append(scale(day)).append(draft.body()).append('\n');

		return new TilNote(title, day.date(), md.toString());
	}

	/**
	 * 본문 머리의 규모 한 줄 — {@code 커밋 2건 · +342 -4 · PR 1건 · 13:14~16:18 · 19:14~19:49}.
	 *
	 * <p>산술을 요약 모델에 맡기지 않는다. 합계를 시키면 틀린 수를 자신 있게 적고, 그것이
	 * 실측처럼 보이는 자리에 남는다. 세션 구간도 같다 — {@link Sessions} 가 이미 가른 것을
	 * 다시 세게 할 이유가 없다.
	 *
	 * <p>공개 커밋이 없는 날은 커밋 절을 통째로 뺀다. private 만 있던 날의 {@code 커밋 0건} 은
	 * 없는 활동을 있는 척하는 표기이고, 그날 실재하는 것은 시각뿐이다.
	 */
	private static String scale(AnonymousDay day) {
		List<String> parts = new ArrayList<>();
		if (!day.commits().isEmpty()) {
			parts.add("커밋 %d건".formatted(day.commits().size()));
			parts.add("+%d -%d".formatted(
					day.commits().stream().mapToInt(AnonymousDay.Commit::additions).sum(),
					day.commits().stream().mapToInt(AnonymousDay.Commit::deletions).sum()));
		}
		if (!day.pullRequests().isEmpty()) {
			parts.add("PR %d건".formatted(day.pullRequests().size()));
		}
		Sessions.split(day.commitTimes()).forEach(session -> parts.add(range(session)));
		return String.join(" · ", parts) + "\n\n";
	}

	/**
	 * 세션 하나의 표기. 커밋 하나뿐인 세션은 시각 하나로 적는다 — {@code 09:12~09:12} 은
	 * 구간이 아니라 같은 값을 두 번 쓴 것이고, 앉아 있던 길이를 모른다는 사실만 흐린다.
	 */
	private static String range(Sessions.Session session) {
		String start = minute(session.start());
		String end = minute(session.end());
		return start.equals(end) ? start : "%s~%s".formatted(start, end);
	}

	private static String minute(Instant at) {
		return TIME.format(LocalTime.ofInstant(at, DayWindow.SEOUL).truncatedTo(ChronoUnit.MINUTES));
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
		day.commits().forEach(commit -> add(names, commit.repo()));
		day.pullRequests().forEach(pull -> add(names, pull.repo()));
		return List.copyOf(names);
	}

	/**
	 * 상한을 넘긴 이름은 태그로 삼지 않는다.
	 *
	 * <p>길이가 리포명에서 오는 값이라 이 도구가 정할 수 없다. 자르면 있지도 않은 리포 이름이
	 * 태그로 남고, 그대로 보내면 그날 기록이 통째로 거부된다 — 태그 하나를 버리는 쪽이 싸다.
	 * 그 이름 자체는 본문에 남아 그날 한 일에서 사라지지는 않는다.
	 */
	private static void add(SequencedSet<String> names, String fullName) {
		String name = shortName(fullName);
		if (name.length() <= MAX_TAG) {
			names.add(name);
		}
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
