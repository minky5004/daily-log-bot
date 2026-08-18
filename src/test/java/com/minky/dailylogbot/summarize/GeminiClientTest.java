package com.minky.dailylogbot.summarize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class GeminiClientTest {

	@Test
	@DisplayName("키가 없으면 첫 호출이 아니라 만드는 자리에서 터진다")
	void missingKeyFailsEarly() {
		assertThrows(IllegalArgumentException.class, () -> new GeminiClient(null));
		assertThrows(IllegalArgumentException.class, () -> new GeminiClient("  "));
	}
}
