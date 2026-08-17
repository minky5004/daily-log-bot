package com.minky.dailylogbot;

import com.minky.dailylogbot.collect.ActivityCollector;
import com.minky.dailylogbot.collect.DailyActivity;
import com.minky.dailylogbot.collect.DayWindow;
import com.minky.dailylogbot.collect.GitHubClient;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
		System.out.printf(
				"커밋 %d건 · PR %d건 · 첫 커밋 %s%n",
				activity.commits().size(),
				activity.pullRequests().size(),
				activity.firstCommitAt().map(KST_TIME::format).orElse("-"));

		if (activity.isEmpty()) {
			System.out.println("올릴 것 없는 날");
		}
		System.out.println();

		printCommits(activity);
		printPullRequests(activity);
	}

	/**
	 * private 리포는 건수로만 찍는다.
	 *
	 * <p>이 리포가 public 이라 워크플로 실행 로그도 로그인 없이 열린다 — 리포명 · 커밋 메시지 ·
	 * 파일 경로를 그대로 찍으면 90일 보존되는 공개 URL 에 private 세부가 남는다. 파이프라인
	 * 안쪽의 익명화는 다음 사이클이 맡지만, 이 사이클이 새로 낸 출력 경로는 이 사이클이 막는다.
	 */
	private static void printCommits(DailyActivity activity) {
		Set<String> hidden = new LinkedHashSet<>();
		int hiddenCommits = 0;

		for (DailyActivity.Commit commit : activity.commits()) {
			if (commit.isPrivate()) {
				hidden.add(commit.repo());
				hiddenCommits++;
				continue;
			}
			System.out.printf(
					"  %s  %s  %s  +%d -%d  %d파일  %s%n",
					KST_TIME.format(commit.committedAt()),
					commit.sha().substring(0, 7),
					commit.repo(),
					commit.additions(),
					commit.deletions(),
					commit.files().size(),
					commit.subject());
			commit.files().forEach(file -> System.out.println("        " + file));
		}

		if (hiddenCommits > 0) {
			System.out.printf("  비공개 저장소 %d곳 · 커밋 %d건%n", hidden.size(), hiddenCommits);
		}
	}

	private static void printPullRequests(DailyActivity activity) {
		if (activity.pullRequests().isEmpty()) {
			return;
		}
		System.out.println();

		Set<String> hidden = new LinkedHashSet<>();
		int hiddenPulls = 0;

		for (DailyActivity.PullRequest pull : activity.pullRequests()) {
			if (pull.isPrivate()) {
				hidden.add(pull.repo());
				hiddenPulls++;
				continue;
			}
			System.out.printf(
					"  %s  %s#%d  %s%n",
					KST_TIME.format(pull.createdAt()), pull.repo(), pull.number(), pull.title());
		}

		if (hiddenPulls > 0) {
			System.out.printf("  비공개 저장소 %d곳 · PR %d건%n", hidden.size(), hiddenPulls);
		}
	}
}
