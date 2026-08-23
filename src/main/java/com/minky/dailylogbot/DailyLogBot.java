package com.minky.dailylogbot;

import com.minky.dailylogbot.anonymize.AnonymousDay;
import com.minky.dailylogbot.anonymize.Anonymizer;
import com.minky.dailylogbot.assemble.NoteAssembler;
import com.minky.dailylogbot.assemble.TilNote;
import com.minky.dailylogbot.collect.ActivityCollector;
import com.minky.dailylogbot.collect.DayWindow;
import com.minky.dailylogbot.collect.GitHubClient;
import com.minky.dailylogbot.summarize.GeminiClient;
import com.minky.dailylogbot.summarize.Summarizer;
import com.minky.dailylogbot.upload.StudyLog;
import com.minky.dailylogbot.upload.StudyLogClient;
import com.minky.dailylogbot.upload.Uploader;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 어제 하루를 모아 마크다운 한 장으로 접어 study-log 에 올린다.
 * 이 실행을 자정마다 밟는 것은 {@code .github/workflows/daily-log.yml} 의 cron 이다.
 */
public class DailyLogBot {

	private static final DateTimeFormatter KST_TIME =
			DateTimeFormatter.ofPattern("HH:mm").withZone(DayWindow.SEOUL);

	public static void main(String[] args) {
		// 판정과 업로드만 건너뛰고 마크다운까지 찍는 실행. 본문 형식을 고치는 사이클이 결과를
		// 보는 통로다 — 이미 올라간 날의 dispatch 는 요약 앞 판정에서 끊겨 노트가 없다.
		// schedule 발화에는 inputs 가 없어 빈 문자열이 오고, 그 값은 여기서 거짓이다
		boolean dryRun = "true".equals(System.getenv("DRY_RUN"));

		GitHubClient github = new GitHubClient(System.getenv("GH_PAT"));

		// 요약 키를 수집 전에 확인한다. 뒤로 미루면 계정 전체를 다 돌고 나서야 키가 없다는 것을
		// 알게 되고, 그 실행은 GitHub 호출만 태운 채 끝난다
		Summarizer summarizer = new Summarizer(new GeminiClient(System.getenv("GEMINI_API_KEY")));

		// study-log 시크릿도 수집 전에 확인한다. 뒤로 미루면 계정 전체를 다 돌고 요약까지 태운
		// 뒤에야 자격이 없다는 것을 알게 된다
		StudyLog studyLog = new StudyLogClient(
				System.getenv("STUDYLOG_BASE_URL"),
				System.getenv("STUDYLOG_USERNAME"),
				System.getenv("STUDYLOG_PASSWORD"));

		String login = github.get("/user", Map.of()).path("login").asText();
		DayWindow window = DayWindow.yesterday(Clock.systemUTC());

		// 수집 결과를 변수로 받지 않는다. 익명화 이전 값이 스코프에 남아 있으면 다음 사이클이
		// 무심코 집어 갈 수 있는 자리가 되고, 그 순간 방어선이 한 자리라는 전제가 깨진다
		AnonymousDay day = Anonymizer.strip(new ActivityCollector(github, login).collect(window));
		print(login, window, day);

		// 설계 4절 — 커밋 0건인 날은 아무것도 올리지 않는다. 요약도 부르지 않는 것은 없는 하루를
		// 넘기면 모델이 무엇이든 지어내기 때문이다. 빈 기록보다 지어낸 기록이 나쁘다
		if (day.isEmpty()) {
			return;
		}

		// 이미 있는 날은 건너뛴다 — 사용자가 아침에 end 를 고쳐 study-log 의 시각 키가 어긋나도
		// 공개 목록으로 보는 판정이라 재실행이 기록을 둘로 만들지 않는다.
		// 요약보다 앞인 것은 백업 발화가 매일 밤 이미 올라간 날을 다시 요약하기 때문이다 —
		// 그 호출은 무엇도 올리지 못하면서 503 확률만 두 배로 만든다
		Uploader uploader = new Uploader(studyLog);
		if (!dryRun && uploader.alreadyPublished(day.date())) {
			System.out.println("이미 있어 건너뜀");
			return;
		}

		TilNote note = NoteAssembler.assemble(day, summarizer.summarize(day));
		print(note);

		// 올리기 직전에 끊는다. 요약과 조립을 실제로 태운 결과라야 형식 확인의 근거가 된다
		if (dryRun) {
			System.out.println("dry-run — 올리지 않음");
			return;
		}

		boolean uploaded = uploader.publish(note);
		System.out.println(uploaded ? "study-log 에 올림" : "이미 있어 건너뜀");
	}

	/**
	 * 익명화를 통과한 것만 찍는다.
	 *
	 * <p>이 리포가 public 이라 워크플로 실행 로그도 로그인 없이 열린다. 출력 경로에 따로 마스킹을
	 * 두지 않는 것은 게을러서가 아니라, 여기 오는 값에 private 세부가 이미 없기 때문이다.
	 */
	private static void print(String login, DayWindow window, AnonymousDay day) {
		AnonymousDay.Hidden hidden = day.hidden();

		System.out.printf("%s · %s (KST) · %s ~ %s%n", login, day.date(), window.since(), window.until());
		System.out.printf(
				"커밋 %d건 · PR %d건 · 첫 커밋 %s%n",
				day.commits().size() + hidden.commits(),
				day.pullRequests().size() + hidden.pullRequests(),
				day.firstCommitAt().map(KST_TIME::format).orElse("-"));

		if (day.isEmpty()) {
			System.out.println("올릴 것 없는 날");
		}
		System.out.println();

		for (AnonymousDay.Commit commit : day.commits()) {
			System.out.printf(
					"  %s  %s  +%d -%d  %d파일  %s%n",
					KST_TIME.format(commit.committedAt()),
					commit.repo(),
					commit.additions(),
					commit.deletions(),
					commit.files().size(),
					commit.subject());
			commit.files().forEach(file -> System.out.println("        " + file));
		}

		if (!day.pullRequests().isEmpty()) {
			System.out.println();
			for (AnonymousDay.PullRequest pull : day.pullRequests()) {
				System.out.printf(
						"  %s  %s#%d  %s%n",
						KST_TIME.format(pull.createdAt()), pull.repo(), pull.number(), pull.title());
			}
		}

		if (!hidden.isEmpty()) {
			System.out.printf("%n  %s%n", hidden.describe());
		}
	}

	/** 올린 마크다운을 그대로 찍는다. public 실행 로그라 익명화를 통과한 것만 여기 온다. */
	private static void print(TilNote note) {
		System.out.printf("%n---- %s · %d자 ----%n%s", note.title(), note.markdown().length(), note.markdown());
	}
}
