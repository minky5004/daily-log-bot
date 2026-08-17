package com.minky.dailylogbot.collect;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 하루치 수집 결과. 익명화 · 요약 · 조립이 이 자리를 입력으로 받는다.
 *
 * <p>private 세부가 그대로 담기는 것은 이 사이클까지다 — 제거는 다음 사이클의 관심사고,
 * 그때 요약 이전 한 자리에서 걸린다.
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
			List<String> files) {

		public String subject() {
			int nl = message.indexOf('\n');
			return nl < 0 ? message : message.substring(0, nl);
		}
	}

	public record PullRequest(
			String repo, boolean isPrivate, int number, String title, String body, Instant createdAt) {}

	public boolean isEmpty() {
		return commits.isEmpty() && pullRequests.isEmpty();
	}

	/** 기록의 {@code start} 가 될 자리. 지어낸 값이 아니라 그날 실제로 남은 첫 흔적이다. */
	public Optional<Instant> firstCommitAt() {
		return commits.stream().map(Commit::committedAt).min(Instant::compareTo);
	}
}
