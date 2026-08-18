package com.minky.dailylogbot.anonymize;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 익명화를 통과한 하루. 요약 · 조립 · 업로드는 이 자리만 본다.
 *
 * <p>private 리포의 리포명 · 커밋 메시지 · 파일 경로를 담을 필드가 아예 없다 — 걸러내기를
 * 잊었는지 매번 확인하는 대신, 담을 자리를 없애 두는 쪽이다.
 */
public record AnonymousDay(
		LocalDate date,
		Optional<Instant> firstCommitAt,
		List<Commit> commits,
		List<PullRequest> pullRequests,
		Hidden hidden) {

	/** public 리포의 커밋. 코드 본문은 여기에도 오지 않는다 — 파일 경로와 증감 줄 수까지다. */
	public record Commit(
			String repo,
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

	public record PullRequest(String repo, int number, String title, String body, Instant createdAt) {}

	/** private 리포에서 남는 전부. 이름이 아니라 개수다. */
	public record Hidden(int repos, int commits, int pullRequests) {

		public boolean isEmpty() {
			return repos == 0;
		}

		/** {@code 비공개 저장소 2곳 · 커밋 7건} 형태. 0인 항목은 빼서 없는 활동을 있는 척하지 않는다. */
		public String describe() {
			StringBuilder sb = new StringBuilder("비공개 저장소 %d곳".formatted(repos));
			if (commits > 0) {
				sb.append(" · 커밋 %d건".formatted(commits));
			}
			if (pullRequests > 0) {
				sb.append(" · PR %d건".formatted(pullRequests));
			}
			return sb.toString();
		}
	}

	/**
	 * 올릴 것이 없는 날인가.
	 *
	 * <p>첫 커밋 시각이 곧 기록의 {@code start} 라, 그 자리가 비었다는 것과 올릴 수 없다는 것이
	 * 같은 말이다. private 커밋만 있던 날은 비어 있지 않다 — 내용은 앙상해도 시각은 실재한다.
	 */
	public boolean isEmpty() {
		return firstCommitAt.isEmpty();
	}
}
