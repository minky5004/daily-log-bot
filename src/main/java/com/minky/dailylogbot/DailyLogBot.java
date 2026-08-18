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

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 중복 방어 → 업로드가 이 자리에 사이클마다 하나씩 붙는다.
 * 지금 보이는 것은 어제 하루가 무슨 마크다운 한 장으로 접히는지까지다.
 */
public class DailyLogBot {

	private static final DateTimeFormatter KST_TIME =
			DateTimeFormatter.ofPattern("HH:mm").withZone(DayWindow.SEOUL);

	public static void main(String[] args) {
		GitHubClient github = new GitHubClient(System.getenv("GH_PAT"));

		// 요약 키를 수집 전에 확인한다. 뒤로 미루면 계정 전체를 다 돌고 나서야 키가 없다는 것을
		// 알게 되고, 그 실행은 GitHub 호출만 태운 채 끝난다
		Summarizer summarizer = new Summarizer(new GeminiClient(System.getenv("GEMINI_API_KEY")));

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
		print(NoteAssembler.assemble(day, summarizer.summarize(day)));
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

	/** 올릴 마크다운을 그대로 찍는다. 문을 통과하는 것은 업로드 사이클 몫이다. */
	private static void print(TilNote note) {
		System.out.printf("%n---- %s · %d자 ----%n%s", note.title(), note.markdown().length(), note.markdown());
	}
}
