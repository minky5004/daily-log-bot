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

		String endpoint = "http://127.0.0.1:%d/model:generateContent".formatted(server.getAddress().getPort());
		return new GeminiClient("key", endpoint, waits::add);
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
	@DisplayName("429 는 다시 치지 않는다 — 하루 한도는 리셋 전까지 풀리지 않는다")
	void quotaIsNotRetried() throws IOException {
		GeminiClient client = answering(429);

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.generate("p", SCHEMA));
		assertTrue(e.getMessage().contains("→ 429"), e.getMessage());
		assertEquals(1, requests.get());
		assertTrue(waits.isEmpty(), waits.toString());
	}

	@Test
	@DisplayName("세 번 다 5xx 면 마지막 응답으로 실패하고 마지막 뒤에는 기다리지 않는다")
	void givesUpWithTheLastAnswer() throws IOException {
		GeminiClient client = answering(500, 502, 503);

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.generate("p", SCHEMA));
		assertTrue(e.getMessage().contains("→ 503"), e.getMessage());
		assertEquals(3, requests.get());
		assertEquals(SPACING, waits);
	}
}
