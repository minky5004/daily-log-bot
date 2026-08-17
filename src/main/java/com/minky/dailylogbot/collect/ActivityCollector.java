package com.minky.dailylogbot.collect;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 설계 5절의 수집 규칙 그 자체. */
public final class ActivityCollector {

	private final GitHubClient github;
	private final String login;
	private final Map<String, Boolean> privacy = new LinkedHashMap<>();

	public ActivityCollector(GitHubClient github, String login) {
		this.github = github;
		this.login = login;
	}

	public DailyActivity collect(DayWindow window) {
		List<DailyActivity.Commit> commits = new ArrayList<>();
		for (JsonNode repo : touchedRepos(window)) {
			commits.addAll(commitsIn(repo, window));
		}
		commits.sort(Comparator.comparing(DailyActivity.Commit::committedAt));
		return new DailyActivity(window, List.copyOf(commits), pullRequests(window));
	}

	/**
	 * {@code pushed_at} 으로 먼저 거른다. 계정 전체를 리포마다 두들기면 대부분이 그날 아무 일도
	 * 없던 리포에 쓰이는 호출이다.
	 */
	private List<JsonNode> touchedRepos(DayWindow window) {
		List<JsonNode> touched = new ArrayList<>();
		for (JsonNode repo : github.getAllPages("/user/repos", Map.of("sort", "pushed"))) {
			privacy.put(repo.path("full_name").asText(), repo.path("private").asBoolean());

			// 한 번도 push 되지 않은 리포는 pushed_at 이 null 이다
			JsonNode pushedAt = repo.path("pushed_at");
			if (pushedAt.isTextual() && !Instant.parse(pushedAt.asText()).isBefore(window.from())) {
				touched.add(repo);
			}
		}
		return touched;
	}

	/**
	 * 브랜치를 순회하고 sha 로 겹침을 걷어낸다. 기본 브랜치만 보면 그날 만들고 아직 머지하지
	 * 않은 기능 브랜치의 커밋이 통째로 사라진다 — 사이클 하나가 자정을 넘기면 바로 그 경우다.
	 */
	private List<DailyActivity.Commit> commitsIn(JsonNode repo, DayWindow window) {
		String fullName = repo.path("full_name").asText();
		boolean isPrivate = repo.path("private").asBoolean();

		Set<String> seen = new LinkedHashSet<>();
		List<DailyActivity.Commit> commits = new ArrayList<>();

		for (JsonNode branch : github.getAllPages("/repos/" + fullName + "/branches", Map.of())) {
			Map<String, String> query = new LinkedHashMap<>();
			query.put("sha", branch.path("name").asText());
			query.put("author", login);
			query.put("since", window.since());
			query.put("until", window.until());

			for (JsonNode commit : github.getAllPages("/repos/" + fullName + "/commits", query)) {
				// 머지 커밋은 자기가 들여온 커밋들의 변경을 first parent 기준으로 한 번 더
				// 들고 있다. 기능 브랜치를 머지 커밋으로만 합치는 흐름이라 PR 을 머지한 날은
				// 같은 파일 · 같은 증감이 두 번 잡히고, 제목도 `Merge pull request #N` 뿐이다
				if (commit.path("parents").size() > 1) {
					continue;
				}
				String sha = commit.path("sha").asText();
				if (seen.add(sha)) {
					commits.add(detail(fullName, isPrivate, sha));
				}
			}
		}
		return commits;
	}

	/** 목록 응답에는 파일도 증감도 없다. 규모를 알려면 커밋마다 상세를 한 번 더 친다. */
	private DailyActivity.Commit detail(String fullName, boolean isPrivate, String sha) {
		JsonNode commit = github.get("/repos/" + fullName + "/commits/" + sha, Map.of());

		List<String> files = new ArrayList<>();
		commit.path("files").forEach(file -> files.add(file.path("filename").asText()));

		// author date 가 아니라 committer date 다. since · until 이 거르는 것이 committer date 라
		// (author 시각만 걸친 창으로는 조회되지 않는 것을 확인), author date 를 담으면 rebase 로
		// 두 시각이 갈린 커밋에서 창 밖 시각이 기록된다 — 그 최솟값이 곧 기록의 start 다
		return new DailyActivity.Commit(
				fullName,
				isPrivate,
				sha,
				Instant.parse(commit.path("commit").path("committer").path("date").asText()),
				commit.path("commit").path("message").asText(),
				commit.path("stats").path("additions").asInt(),
				commit.path("stats").path("deletions").asInt(),
				List.copyOf(files));
	}

	/**
	 * PR 은 리포 순회 대신 검색 한 번이다. 커밋과 달리 열린 자리가 한 곳뿐이라 커버리지가
	 * 갈리지 않는다.
	 */
	private List<DailyActivity.PullRequest> pullRequests(DayWindow window) {
		String q = "author:%s type:pr created:%s..%s".formatted(login, window.since(), window.until());
		JsonNode found = github.get("/search/issues", Map.of("q", q, "per_page", "100"));

		List<DailyActivity.PullRequest> pulls = new ArrayList<>();
		for (JsonNode item : found.path("items")) {
			String repo = item.path("repository_url").asText().replace("https://api.github.com/repos/", "");
			JsonNode body = item.path("body");
			pulls.add(new DailyActivity.PullRequest(
					repo,
					isPrivate(repo),
					item.path("number").asInt(),
					item.path("title").asText(),
					body.isTextual() ? body.asText() : "",
					Instant.parse(item.path("created_at").asText())));
		}
		pulls.sort(Comparator.comparing(DailyActivity.PullRequest::createdAt));
		return List.copyOf(pulls);
	}

	/**
	 * 검색 응답은 리포의 공개 여부를 주지 않는다. 익명화가 이 값에 걸리므로 목록에 없던 리포는
	 * 따로 물어본다 — 브랜치만 밀어 두고 다음 날 연 PR 이 그 경우다.
	 */
	private boolean isPrivate(String fullName) {
		return privacy.computeIfAbsent(
				fullName, name -> github.get("/repos/" + name, Map.of()).path("private").asBoolean());
	}
}
