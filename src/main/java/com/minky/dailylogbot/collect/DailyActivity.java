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

	/**
	 * 올릴 것이 없는 날인가.
	 *
	 * <p>PR 이 있어도 커밋이 0건이면 비어 있는 것으로 본다 — 기록의 {@code start} 는 그날 첫
	 * 커밋 시각이라, 커밋이 없으면 시각을 정할 근거 자체가 없다. 밤에 브랜치만 밀어 두고 다음
	 * 날 PR 을 여는 흐름이 실제로 그 경우다.
	 */
	public boolean isEmpty() {
		return commits.isEmpty();
	}

	/** 기록의 {@code start} 가 될 자리. 지어낸 값이 아니라 그날 실제로 남은 첫 흔적이다. */
	public Optional<Instant> firstCommitAt() {
		return commits.stream().map(Commit::committedAt).min(Instant::compareTo);
	}
}
