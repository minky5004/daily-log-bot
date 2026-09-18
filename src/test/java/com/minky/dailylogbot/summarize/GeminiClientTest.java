package com.minky.dailylogbot.summarize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiClientTest {

	private static final JsonNode SCHEMA = new ObjectMapper().createObjectNode();

	/** 본문 한 덩이가 STOP 으로 끝난 200 응답. */
	private static final String OK = """
			{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"본문"}]}}]}""";

	private static final List<Duration> SPACING = List.of(Duration.ofSeconds(30), Duration.ofSeconds(60));

	private final AtomicInteger requests = new AtomicInteger();
	/** 요청마다 두드린 모델. 경로의 {@code models/} 와 {@code :} 사이다. */
	private final List<String> models = new ArrayList<>();
	private final List<Duration> waits = new ArrayList<>();
	private HttpServer server;

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	/** 상태 코드를 차례로 돌려주는 가짜 Gemini. 대기는 재지 않고 기록만 한다. */
	private GeminiClient answering(int... statuses) throws IOException {
		Deque<Integer> queue = new ArrayDeque<>();
		for (int status : statuses) {
			queue.add(status);
		}

		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requests.incrementAndGet();
			String path = exchange.getRequestURI().getPath();
			models.add(path.substring(path.lastIndexOf('/') + 1, path.indexOf(':')));
			exchange.getRequestBody().readAllBytes();
			// 준비한 것보다 많이 치면 요청 수 단언이 잡는다
			int status = queue.isEmpty() ? 500 : queue.poll();
			byte[] body = (status == 200 ? OK : "{\"error\":{\"code\":%d}}".formatted(status))
					.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();

		String base = "http://127.0.0.1:%d/models/".formatted(server.getAddress().getPort());
		return new GeminiClient("key", base, waits::add);
	}

	@Test
	@DisplayName("키가 없으면 첫 호출이 아니라 만드는 자리에서 터진다")
	void missingKeyFailsEarly() {
		assertThrows(IllegalArgumentException.class, () -> new GeminiClient(null));
		assertThrows(IllegalArgumentException.class, () -> new GeminiClient("  "));
	}

	@Test
	@DisplayName("503 은 30초 · 60초 간격으로 다시 쳐서 받아 온다")
	void overloadIsRetriedWithSpacing() throws IOException {
		GeminiClient client = answering(503, 503, 200);

		assertEquals("본문", client.generate("p", SCHEMA));
		assertEquals(3, requests.get());
		assertEquals(SPACING, waits);
	}

	@Test
	@DisplayName("429 는 같은 모델로 다시 치지 않고 다른 모델로 한 번 넘어간다")
	void quotaFallsBackWithoutRetry() throws IOException {
		// 하루 한도는 리셋 전까지 풀리지 않지만 모델마다 따로 선다
		GeminiClient client = answering(429, 200);

		assertEquals("본문", client.generate("p", SCHEMA));
		assertEquals(List.of("gemini-3.6-flash", "gemini-2.5-flash"), models);
		assertTrue(waits.isEmpty(), waits.toString());
	}

	@Test
	@DisplayName("세 번 다 5xx 면 다른 모델로 한 번 더 쳐서 받아 온다")
	void overloadFallsBackToAnotherModel() throws IOException {
		GeminiClient client = answering(500, 502, 503, 200);

		assertEquals("본문", client.generate("p", SCHEMA));
		assertEquals(List.of("gemini-3.6-flash", "gemini-3.6-flash", "gemini-3.6-flash", "gemini-2.5-flash"), models);
		assertEquals(SPACING, waits);
	}

	@Test
	@DisplayName("둘 다 막히면 두 모델의 실패를 함께 싣고 폴백은 한 번만 친다")
	void givesUpWithBothFailures() throws IOException {
		GeminiClient client = answering(503, 503, 503, 503);

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.generate("p", SCHEMA));
		assertTrue(e.getMessage().contains("gemini-3.6-flash → 503"), e.getMessage());
		assertTrue(e.getMessage().contains("gemini-2.5-flash → 503"), e.getMessage());
		assertEquals(4, requests.get());
		assertEquals(SPACING, waits);
	}

	@Test
	@DisplayName("400 은 모델을 바꿔도 같아 넘어가지 않는다")
	void badRequestDoesNotFallBack() throws IOException {
		GeminiClient client = answering(400);

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.generate("p", SCHEMA));
		assertTrue(e.getMessage().contains("→ 400"), e.getMessage());
		assertEquals(1, requests.get());
	}
}
