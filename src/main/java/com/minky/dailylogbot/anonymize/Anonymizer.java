package com.minky.dailylogbot.anonymize;

import com.minky.dailylogbot.collect.DailyActivity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 설계 5절의 익명화. private 리포는 건수만 남기고 세부를 버린다.
 *
 * <p>요약 이전에 거는 것은 study-log 가 읽기 공개인 것이 1차 이유지만, 이 지점에서 자르면
 * private 세부가 요약 API 로도 나가지 않는다 — 방어선 하나로 둘을 덮는다.
 *
 * <p>시각만은 버리지 않는다. 활동이 있었다는 사실이지 활동의 내용이 아니고, 기록의 {@code start}
 * 를 정할 근거가 그것뿐이다.
 */
public final class Anonymizer {

	private Anonymizer() {}

	public static AnonymousDay strip(DailyActivity activity) {
		List<AnonymousDay.Commit> commits = new ArrayList<>();
		List<AnonymousDay.PullRequest> pulls = new ArrayList<>();

		// 리포명은 세는 동안만 쥔다. 결과에는 개수만 넘어간다
		Set<String> hiddenRepos = new LinkedHashSet<>();
		int hiddenCommits = 0;
		int hiddenPulls = 0;

		for (DailyActivity.Commit commit : activity.commits()) {
			if (commit.isPrivate()) {
				hiddenRepos.add(commit.repo());
				hiddenCommits++;
				continue;
			}
			commits.add(new AnonymousDay.Commit(
					commit.repo(),
					commit.committedAt(),
					commit.message(),
					commit.additions(),
					commit.deletions(),
					commit.files()));
		}

		for (DailyActivity.PullRequest pull : activity.pullRequests()) {
			if (pull.isPrivate()) {
				hiddenRepos.add(pull.repo());
				hiddenPulls++;
				continue;
			}
			pulls.add(new AnonymousDay.PullRequest(
					pull.repo(), pull.number(), pull.title(), pull.body(), pull.createdAt()));
		}

		// 커밋과 PR 을 합쳐 센다. 같은 리포를 양쪽에서 만나 "저장소 2곳" 이 되면 리포 하나짜리
		// 하루가 둘로 부풀고, 그 수가 곧 요약에 들어가는 문장이다
		return new AnonymousDay(
				activity.window().date(),
				activity.commitTimes(),
				List.copyOf(commits),
				List.copyOf(pulls),
				new AnonymousDay.Hidden(hiddenRepos.size(), hiddenCommits, hiddenPulls));
	}
}
