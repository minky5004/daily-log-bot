package com.minky.dailylogbot;

import com.minky.dailylogbot.anonymize.AnonymousDay;
import com.minky.dailylogbot.anonymize.Anonymizer;
import com.minky.dailylogbot.collect.ActivityCollector;
import com.minky.dailylogbot.collect.DailyActivity;
import com.minky.dailylogbot.collect.DayWindow;
import com.minky.dailylogbot.collect.GitHubClient;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 요약 → 조립 → 중복 방어 → 업로드가 이 자리에 사이클마다 하나씩 붙는다.
 * 지금 보이는 것은 어제 하루가 익명화를 통과한 뒤 무엇으로 남는지까지다.
 */
public class DailyLogBot {

	private static final DateTimeFormatter KST_TIME =
			DateTimeFormatter.ofPattern("HH:mm").withZone(DayWindow.SEOUL);

	public static void main(String[] args) {
		GitHubClient github = new GitHubClient(System.getenv("GH_PAT"));
		String login = github.get("/user", Map.of()).path("login").asText();

		DayWindow window = DayWindow.yesterday(Clock.systemUTC());
		DailyActivity activity = new ActivityCollector(github, login).collect(window);

		print(login, window, Anonymizer.strip(activity));
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
}
