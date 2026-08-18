package com.minky.dailylogbot.summarize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Gemini REST 를 텍스트 한 덩이로만 돌려주는 얇은 통로. 무엇을 묻고 무엇을 받을지는
 * {@link Summarizer} 몫이다 — 수집 쪽의 {@code GitHubClient} 와 {@code ActivityCollector} 가
 * 나뉜 것과 같은 선이다.
 *
 * <p>SDK 대신 REST 를 직접 치는 것은 이 프로젝트의 외부 호출이 전부 JDK {@code HttpClient} 라
 * 서다. 쓰는 기능이 생성 호출 하나뿐이라 SDK 가 덜어 줄 것이 스키마 빌더 정도인데, 그 대가로
 * 의존 트리가 수십 개 늘어난다.
 */
public final class GeminiClient implements SummaryModel {

	/** 무료 티어. 하루 한 번 호출이라 한도가 판단 근거가 되지 못한다. */
	private static final String MODEL = "gemini-3.6-flash";

	private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

	/*
	  출력 상한. 요약 자체는 짧지만 사고 토큰이 이 예산에서 함께 나간다 — 응답 길이에 맞춰
	  잡으면 본문이 시작되기도 전에 잘린다. 무료 티어의 한도는 호출 수와 분당 토큰이라
	  쓰지 않은 상한에는 값이 붙지 않는다.
	*/
	private static final int MAX_OUTPUT_TOKENS = 8000;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	private final ObjectMapper mapper = new ObjectMapper();
	private final String apiKey;

	public GeminiClient(String apiKey) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalArgumentException("GEMINI_API_KEY 없음 — 요약에 필요하다");
		}
		this.apiKey = apiKey;
	}

	/**
	 * 스키마에 맞춘 JSON 문자열을 받아 온다.
	 *
	 * <p>JSON 을 프롬프트로 부탁하고 파싱 실패마다 재시도하는 코드를 두느니 API 가 형태를
	 * 보장하게 한다. 그래도 내용이 우리 기대와 맞는지는 부르는 쪽이 확인한다.
	 */
	@Override
	public String generate(String prompt, JsonNode responseSchema) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + MODEL + ":generateContent"))
				// 키를 질의 문자열이 아니라 헤더로 보낸다. URL 은 실패 메시지와 함께 그대로
				// 공개 실행 로그에 남는 자리다
				.header("x-goog-api-key", apiKey)
				.header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(120))
				.POST(HttpRequest.BodyPublishers.ofString(
						body(prompt, responseSchema), StandardCharsets.UTF_8))
				.build();

		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}

		if (response.statusCode() != 200) {
			throw new IllegalStateException(
					"Gemini %s → %d %s".formatted(MODEL, response.statusCode(), response.body()));
		}
		return text(read(response.body()));
	}

	private String body(String prompt, JsonNode responseSchema) {
		ObjectNode root = mapper.createObjectNode();
		root.putArray("contents").addObject().putArray("parts").addObject().put("text", prompt);

		ObjectNode config = root.putObject("generationConfig");
		config.put("responseMimeType", "application/json");
		config.set("responseSchema", responseSchema);
		config.put("maxOutputTokens", MAX_OUTPUT_TOKENS);

		return root.toString();
	}

	/**
	 * 응답에서 본문 한 덩이를 꺼낸다.
	 *
	 * <p>{@code finishReason} 을 먼저 본다. 상한에 걸려 끊긴 응답은 잘린 JSON 이라 파싱
	 * 실패로만 드러나는데, 그 메시지로는 상한이 원인인지 모델이 형태를 어긴 것인지 갈리지
	 * 않는다.
	 */
	private static String text(JsonNode response) {
		JsonNode candidate = response.path("candidates").path(0);
		String finish = candidate.path("finishReason").asText("");
		if (!finish.isEmpty() && !"STOP".equals(finish)) {
			throw new IllegalStateException("Gemini 응답 중단 — finishReason %s".formatted(finish));
		}

		String text = candidate.path("content").path("parts").path(0).path("text").asText("");
		if (text.isBlank()) {
			throw new IllegalStateException("Gemini 빈 응답 — " + response);
		}
		return text;
	}

	private JsonNode read(String json) {
		try {
			return mapper.readTree(json);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
