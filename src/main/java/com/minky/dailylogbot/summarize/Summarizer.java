package com.minky.dailylogbot.summarize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.minky.dailylogbot.anonymize.AnonymousDay;
import com.minky.dailylogbot.collect.DayWindow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 익명화를 통과한 하루를 TIL 초안 한 편으로 옮긴다.
 *
 * <p>입력이 {@link AnonymousDay} 뿐이라 무엇을 빼야 하는지 여기서 다시 판단하지 않는다. private
 * 세부를 담을 필드가 애초에 없다.
 */
public final class Summarizer {

	private static final DateTimeFormatter KST_TIME =
			DateTimeFormatter.ofPattern("HH:mm").withZone(DayWindow.SEOUL);

	/** 설계 6절의 요약 길이 상한. 넘기면 study-log 가 그 파일을 거부한다. */
	private static final int MAX_SUMMARY = 500;

	/*
	  프롬프트에 싣는 커밋당 파일 수 · 커밋 메시지와 PR 본문의 길이 상한.

	  하루 커밋이 몇 건이라 평소에는 걸리지 않지만, 생성물이나 의존 잠금 파일이 한 커밋에
	  수백 개씩 딸려 오는 날이 있다. 메시지 쪽도 같다 — squash 머지 커밋은 PR 본문을 통째로
	  메시지로 이고 오는데 부모가 하나라 머지 제외 규칙에 걸리지 않는다. 그런 덩어리는 요약에
	  보태는 것이 없으면서 분당 토큰 한도만 밀어 올린다. 넘친 몫은 개수로 알려 "더 있었다" 는
	  사실 자체는 남긴다.
	*/
	private static final int MAX_FILES = 20;

	/*
	  본문 상한. PR 본문은 `## 무엇을 / ## 어떻게 / ## 검증` 세 절이고 실측값은 마지막 절에
	  있다 — 500자에서 자르면 판단만 남고 근거가 사라진다(실측 779~891자).
	*/
	private static final int MAX_TEXT = 1200;

	private static final String RULES = """
			너는 개발자의 하루 활동을 TIL 기록으로 옮기는 기록자다. 아래 활동만 근거로 삼는다.

			규칙
			- 목록에 없는 사실을 지어내지 않는다. 커밋 메시지 · PR 본문 · 파일 경로에 적힌 것까지만 쓴다
			- 무엇을 했는지에 더해 왜 그렇게 했는지를 쓴다. 근거는 주어진 본문에서만 가져오고, 없으면 무엇을 했는지로 끝낸다
			- 재료가 얕은 날은 짧게 끝낸다. 분량을 채우려고 같은 말을 다시 쓰거나 일반론을 붙이지 않는다
			- 소감 · 다짐 · 학습 조언을 붙이지 않는다
			- 비공개 활동은 주어진 집계 문장을 그대로 한 줄 적고 내용을 추측하지 않는다
			- summary 는 한 문장 · 100자 이내 · 그날을 한 줄로 가리키는 말
			- body 는 마크다운 · 저장소마다 `## 저장소명` · 그 아래 `-` 불릿 · 전체 2000자 이내
			- 불릿 하나는 세 문장 안팎이다 — 무엇을 했는지 · 왜 그렇게 했는지 · 본문에 실측값이나 검증 결과가 있으면 그중 하나
			- 불릿 끝에 괄호로 링크와 증감을 단다 — PR 이 있으면 `([PR #41](주어진 링크) · +141 -4)` · 없으면 `(+78 -15)`
			- 저장소 이름 · PR 번호 · 링크는 주어진 그대로 쓴다

			활동
			""";

	/**
	 * 공개 활동이 하나도 없는 날에만 덧붙인다.
	 *
	 * <p>이런 날은 저장소 이름이 프롬프트에 한 번도 나오지 않는데, 규칙은 저장소마다 절을 만들라고
	 * 시킨다. 지시를 지키려면 모델은 없는 이름을 지어내는 수밖에 없다 — 상충을 남겨 두고 결과를
	 * 검사로 잡으려 들면, 지어낸 이름은 형태가 멀쩡해서 {@link #brokenReason} 을 그대로 통과한다.
	 */
	private static final String PUBLIC_NONE =
			"\n공개 활동이 없는 날이다. 저장소 절을 만들지 말고 위 집계 한 줄만 본문으로 쓴다.\n";

	private final SummaryModel model;
	private final ObjectMapper mapper = new ObjectMapper();

	public Summarizer(SummaryModel model) {
		this.model = model;
	}

	public TilDraft summarize(AnonymousDay day) {
		TilDraft draft = parse(model.generate(prompt(day), schema()));

		String broken = brokenReason(draft);
		if (broken != null) {
			throw new IllegalStateException("요약을 쓸 수 없다 — " + broken);
		}
		return draft;
	}

	/**
	 * 하루를 프롬프트 한 장으로 편다.
	 *
	 * <p>줄바꿈을 {@code %n} 이 아니라 {@code \n} 으로 두는 것은 이 문자열이 콘솔이 아니라 API 로
	 * 가기 때문이다. 플랫폼을 타면 로컬에서 만든 프롬프트와 러너에서 만든 프롬프트가 달라진다.
	 *
	 * <p>패키지 접근으로 열어 둔 것은 테스트가 부르기 위해서다. 무엇이 실려 나가는지를 실제
	 * 호출로 확인하려 들면 확인할 때마다 무료 티어 한도를 태운다.
	 */
	static String prompt(AnonymousDay day) {
		StringBuilder sb = new StringBuilder(RULES);
		sb.append("날짜 %s (KST) · 첫 커밋 %s\n".formatted(
				day.date(), day.firstCommitAt().map(KST_TIME::format).orElse("-")));

		if (!day.commits().isEmpty()) {
			sb.append("\n커밋\n");
			for (AnonymousDay.Commit commit : day.commits()) {
				sb.append("- %s %s · +%d -%d\n  %s\n".formatted(
						commit.repo(),
						KST_TIME.format(commit.committedAt()),
						commit.additions(),
						commit.deletions(),
						indent(clip(commit.message(), MAX_TEXT))));
				sb.append("  파일: %s\n".formatted(files(commit.files())));
			}
		}

		if (!day.pullRequests().isEmpty()) {
			sb.append("\nPR\n");
			for (AnonymousDay.PullRequest pull : day.pullRequests()) {
				sb.append("- %s#%d %s · %s\n  링크: %s\n".formatted(
						pull.repo(), pull.number(), KST_TIME.format(pull.createdAt()), pull.title(),
						"https://github.com/%s/pull/%d".formatted(pull.repo(), pull.number())));
				if (pull.body() != null && !pull.body().isBlank()) {
					sb.append("  %s\n".formatted(indent(clip(pull.body(), MAX_TEXT))));
				}
			}
		}

		if (!day.hidden().isEmpty()) {
			sb.append("\n비공개\n- %s\n".formatted(day.hidden().describe()));
		}

		if (day.commits().isEmpty() && day.pullRequests().isEmpty()) {
			sb.append(PUBLIC_NONE);
		}
		return sb.toString();
	}

	/** 여러 줄짜리 값이 항목 사이로 흘러나오지 않게 이어지는 줄을 들여쓴다. */
	private static String indent(String text) {
		return text.strip().replace("\n", "\n  ");
	}

	private static String files(List<String> files) {
		if (files.size() <= MAX_FILES) {
			return String.join(", ", files);
		}
		return String.join(", ", files.subList(0, MAX_FILES))
				+ " 외 %d개".formatted(files.size() - MAX_FILES);
	}

	/**
	 * 상한을 넘긴 글을 자른다.
	 *
	 * <p>경계에 이모지가 걸리면 한 칸 앞에서 끊는다. 자바의 한 글자는 코드 유닛이라 그냥 자르면
	 * 짝을 잃은 대리 문자가 남고, UTF-8 로 나가면서 {@code ?} 한 개로 바뀐다.
	 */
	private static String clip(String text, int max) {
		if (text.length() <= max) {
			return text;
		}
		int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
		return text.substring(0, end) + " …";
	}

	/**
	 * 응답 형태를 API 가 보장하게 한다.
	 *
	 * <p>길이 · 문체 같은 내용 지침은 여기 적지 않는다. 두 곳에 적으면 한쪽만 고쳐 놓고 서로 다른
	 * 규격을 동시에 넘기게 된다 — 무엇을 쓸지는 규칙 블록이 전담한다.
	 */
	private ObjectNode schema() {
		ObjectNode root = mapper.createObjectNode();
		root.put("type", "OBJECT");

		ObjectNode properties = root.putObject("properties");
		properties.putObject("summary").put("type", "STRING").put("description", "하루를 가리키는 한 문장");
		properties.putObject("body").put("type", "STRING").put("description", "마크다운 본문");

		root.putArray("required").add("summary").add("body");
		return root;
	}

	private TilDraft parse(String json) {
		JsonNode node;
		try {
			node = mapper.readTree(json);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return new TilDraft(
				node.path("summary").asText("").strip(), node.path("body").asText("").strip());
	}

	/**
	 * 기록으로 쓸 수 없으면 그 사유를, 멀쩡하면 {@code null} 을 돌려준다.
	 *
	 * <p>상한을 넘긴 요약을 잘라 담지 않는 것은 문장이 중간에서 끊긴 기록이 실패보다 눈에 덜 띄기
	 * 때문이다. 실패는 액션 메일로 그날 안에 알려지고, 잘린 요약은 사이트에 남는다.
	 *
	 * <p>사유에 받은 값을 함께 남긴다. 길이만 찍으면 모델이 무엇을 줬는지 알 수 없어 다음에 손볼
	 * 근거가 없다.
	 */
	static String brokenReason(TilDraft draft) {
		if (draft.summary().isBlank()) {
			return "요약이 비어 있다";
		}
		if (draft.summary().length() > MAX_SUMMARY) {
			return "요약이 %d자다 (상한 %d자): \"%s\""
					.formatted(draft.summary().length(), MAX_SUMMARY, draft.summary());
		}
		if (draft.body().isBlank()) {
			return "본문이 비어 있다";
		}
		return null;
	}
}
