package com.minky.dailylogbot.collect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitHubClientTest {

	@Test
	@DisplayName("실패 메시지의 리포명은 지우고 엔드포인트 종류는 남긴다")
	void repoNameNeverReachesTheLog() {
		assertEquals(
				"/repos/***/commits/1a2b3c4",
				GitHubClient.redact("/repos/minky5004/secret-side-project/commits/1a2b3c4"));
		assertEquals("/repos/***/branches", GitHubClient.redact("/repos/minky5004/UnityStudy/branches"));
		assertEquals("/repos/***", GitHubClient.redact("/repos/minky5004/UnityStudy"));
	}

	@Test
	@DisplayName("리포 경로가 아닌 곳은 그대로 — 계정 · 검색은 가릴 것이 없다")
	void otherPathsStayReadable() {
		assertEquals("/user/repos", GitHubClient.redact("/user/repos"));
		assertEquals("/search/issues", GitHubClient.redact("/search/issues"));
	}
}
