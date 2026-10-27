package com.minky.dailylogbot.collect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityCollectorTest {

	@Test
	@DisplayName("코테 풀이 리포는 수집 대상 밖 — 개발 기록이 아니라 문제 풀이")
	void codingTestIsNotDevelopment() {
		assertTrue(ActivityCollector.excluded("minky5004/coding-test"));
	}

	@Test
	@DisplayName("그 밖의 리포는 그대로 수집")
	void otherReposStayCollected() {
		assertFalse(ActivityCollector.excluded("minky5004/study-log"));
		assertFalse(ActivityCollector.excluded("minky5004/coding-test-old"));
	}
}
