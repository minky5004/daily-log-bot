package com.minky.dailylogbot;

import com.minky.dailylogbot.collect.ActivityCollector;
import com.minky.dailylogbot.collect.DailyActivity;
import com.minky.dailylogbot.collect.DayWindow;
import com.minky.dailylogbot.collect.GitHubClient;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 익명화 → 요약 → 조립 → 중복 방어 → 업로드가 이 자리에 사이클마다 하나씩 붙는다.
 * 지금 보이는 것은 어제 하루가 무엇으로 이루어져 있었는지까지다.
 */
public class DailyLogBot {

	private static final DateTimeFormatter KST_TIME =
			DateTimeFormatter.ofPattern("HH:mm").withZone(DayWindow.SEOUL);

	public static void main(String[] args) {
		GitHubClient github = new GitHubClient(System.getenv("GH_PAT"));
		String login = github.get("/user", Map.of()).path("login").asText();

		DayWindow window = DayWindow.yesterday(Clock.systemUTC());
		DailyActivity activity = new ActivityCollector(github, login).collect(window);

		print(login, activity);
	}

	private static void print(String login, DailyActivity activity) {
		DayWindow window = activity.window();
		System.out.printf(
				"%s · %s (KST) · %s ~ %s%n", login, window.date(), window.since(), window.until());

		if (activity.isEmpty()) {
			System.out.println("커밋 0건 · PR 0건 — 올릴 것 없는 날");
			return;
		}

		System.out.printf(
				"커밋 %d건 · PR %d건 · 첫 커밋 %s%n%n",
				activity.commits().size(),
				activity.pullRequests().size(),
				activity.firstCommitAt().map(KST_TIME::format).orElse("-"));

		for (DailyActivity.Commit commit : activity.commits()) {
			System.out.printf(
					"  %s  %s  %s  +%d -%d  %d파일  %s%n",
					time(commit.committedAt()),
					commit.sha().substring(0, 7),
					mark(commit.repo(), commit.isPrivate()),
					commit.additions(),
					commit.deletions(),
					commit.files().size(),
					commit.subject());
			commit.files().forEach(file -> System.out.println("        " + file));
		}

		if (!activity.pullRequests().isEmpty()) {
			System.out.println();
			for (DailyActivity.PullRequest pull : activity.pullRequests()) {
				System.out.printf(
						"  %s  %s#%d  %s%n",
						time(pull.createdAt()), mark(pull.repo(), pull.isPrivate()), pull.number(), pull.title());
			}
		}
	}

	private static String time(Instant at) {
		return KST_TIME.format(at);
	}

	private static String mark(String repo, boolean isPrivate) {
		return isPrivate ? repo + "(private)" : repo;
	}
}
