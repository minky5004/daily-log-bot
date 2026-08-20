package com.minky.dailylogbot.collect;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 하루치 수집 결과. private 세부가 그대로 담긴 마지막 자리다 — 다음 손을 타는 곳은 익명화뿐이고,
 * 요약부터는 그것을 통과한 형태만 본다.
 */
public record DailyActivity(DayWindow window, List<Commit> commits, List<PullRequest> pullRequests) {

	public record Commit(
			String repo,
			boolean isPrivate,
			String sha,
			Instant committedAt,
			String message,
			int additions,
			int deletions,
			List<String> files) {}

	public record PullRequest(
			String repo, boolean isPrivate, int number, String title, String body, Instant createdAt) {}

	/** 기록의 {@code start} 가 될 자리. 지어낸 값이 아니라 그날 실제로 남은 첫 흔적이다. */
	/** private 를 포함한 그날 커밋 시각 전부. 시간순이라 세션을 가르는 쪽이 정렬을 다시 하지 않는다. */
	public List<Instant> commitTimes() {
		return commits.stream().map(Commit::committedAt).sorted().toList();
	}

	public Optional<Instant> firstCommitAt() {
		return commits.stream().map(Commit::committedAt).min(Instant::compareTo);
	}
}
