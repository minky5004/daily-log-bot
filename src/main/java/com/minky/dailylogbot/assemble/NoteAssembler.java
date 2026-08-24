package com.minky.dailylogbot.assemble;

import com.minky.dailylogbot.anonymize.AnonymousDay;
import com.minky.dailylogbot.collect.DayWindow;
import com.minky.dailylogbot.summarize.TilDraft;

import java.time.Instant;
import java.time.LocalDate;
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

	/**
	 * 그날 기록의 제목. study-log 중복 판정 키의 절반이라 짓는 규칙이 여기 한 곳에만 있다 —
	 * 조회하는 쪽이 제 손으로 같은 문자열을 만들면 둘이 갈리는 날 같은 하루가 기록 둘이 된다.
	 *
	 * <p>요약을 부르기 전에 「이미 올라간 날인가」를 물으려면 초안 없이 제목이 필요해서 갈랐다.
	 *
	 * <p>README 머리의 산출물 링크가 이 문자열을 제목 검색어로 박아 두었다 — 문구를 바꾸면 그
	 * 링크는 404 가 아니라 빈 목록을 200 으로 돌려주고, 읽는 사람에게는 봇이 아무것도 만들지
	 * 못한 화면으로 보인다. 바꿀 일이 생기면 README 를 같은 커밋에서 고친다.
	 */
	public static String title(LocalDate date) {
		return "%s 개발 기록".formatted(date);
	}

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
		String title = title(day.date());

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
	 * 본문 머리의 규모 한 줄 — {@code 커밋 2건 · +342 -4 · PR 1건}.
	 *
	 * <p>산술을 요약 모델에 맡기지 않는다. 합계를 시키면 틀린 수를 자신 있게 적고, 그것이
	 * 실측처럼 보이는 자리에 남는다.
	 *
	 * <p>앉아 있던 구간은 싣지 않는다. 프론트매터 {@code end} 는 실제 시각이 아니라
	 * {@code start + 앉은 시간 합} 이라, 진짜 시각을 옆에 세우면 한 노트가 두 시각을 말한다 —
	 * {@code end: "16:51"} 아래에 {@code 19:14~19:49} 가 서는 식이다. 실제 종료 시각을 쓰려면
	 * study-log 의 duration 이 공백까지 세는 통짜가 되므로, 그 선택은 {@link Sessions} 를 들인
	 * 사이클이 이미 반대쪽으로 정했다.
	 *
	 * <p>건수는 private 까지 센다. 옆에 붙는 구간이 private 커밋 시각으로도 그려져서, 건수만
	 * 공개분이면 한 줄 안에서 분모가 갈린다 — 공개 2건 · 비공개 6건인 날이 {@code 커밋 2건 ·
	 * 09:00~23:40} 이 되어 두 건이 14시간을 만든 것처럼 읽힌다. {@code DailyLogBot} 의 실행
	 * 로그도 같은 합으로 찍는다.
	 *
	 * <p>증감만은 공개분이다. private 리포의 줄 수는 익명화가 떨어뜨려 셀 근거가 없고, 없는
	 * 값을 0으로 적으면 {@code +0 -0} 이 실측처럼 남는다 — 그래서 공개 커밋이 없는 날은 증감
	 * 자체를 뺀다.
	 */
	private static String scale(AnonymousDay day) {
		List<String> parts = new ArrayList<>();
		int commits = day.commits().size() + day.hidden().commits();
		if (commits > 0) {
			parts.add("커밋 %d건".formatted(commits));
		}
		if (!day.commits().isEmpty()) {
			parts.add("+%d -%d".formatted(
					day.commits().stream().mapToInt(AnonymousDay.Commit::additions).sum(),
					day.commits().stream().mapToInt(AnonymousDay.Commit::deletions).sum()));
		}
		if (!day.pullRequests().isEmpty()) {
			parts.add("PR %d건".formatted(day.pullRequests().size()));
		}
		return String.join(" · ", parts) + "\n\n";
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
