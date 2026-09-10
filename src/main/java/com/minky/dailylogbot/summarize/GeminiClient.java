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
import java.util.List;
import java.util.function.Consumer;

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

	/**
	 * 5xx 를 다시 치기 전의 대기. 길이가 곧 재시도 횟수라 세 번까지 친다.
	 *
	 * <p>503 high demand 가 1차 발행을 네 번 죽였다(8/19 · 8/22 · 9/08 · 9/09). 과부하는 끊겼다
	 * 이어지는 형태라 — 9/09 같은 모델을 쓰던 ai-cards-news 는 4분 사이 다섯 호출 중 둘이
	 * 통과했다 — 한 번 더 두드릴 값이 있다. 다만 1초 간격이면 세 번을 같은 스파이크 안에서 다
	 * 쓰므로(ai-cards-news 9/08 · 1초 뒤 재시도도 503) 분 단위로 벌린다.
	 *
	 * <p>한도가 막지 않는다. 하루 한 번 호출이라 1차 · 백업이 세 번씩 채워도 6/20 이고, 30초
	 * 간격이면 분당 5회에 닿지 않는다. 흔들림을 두지 않는 것도 같은 이유다 — 같은 순간에 몰려
	 * 되칠 다른 클라이언트가 이 키에 없다.
	 */
	private static final List<Duration> RETRY_WAITS = List.of(Duration.ofSeconds(30), Duration.ofSeconds(60));

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	private final ObjectMapper mapper = new ObjectMapper();
	private final String apiKey;
	private final String endpoint;
	private final Consumer<Duration> pause;

	public GeminiClient(String apiKey) {
		this(apiKey, BASE + MODEL + ":generateContent", GeminiClient::sleep);
	}

	/** 주소와 대기를 바꿔 끼우는 자리. 테스트가 가짜 서버를 두고 대기는 기록만 한다. */
	GeminiClient(String apiKey, String endpoint, Consumer<Duration> pause) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalArgumentException("GEMINI_API_KEY 없음 — 요약에 필요하다");
		}
		this.apiKey = apiKey;
		this.endpoint = endpoint;
		this.pause = pause;
	}

	/**
	 * 스키마에 맞춘 JSON 문자열을 받아 온다.
	 *
	 * <p>JSON 을 프롬프트로 부탁하고 파싱 실패마다 재시도하는 코드를 두느니 API 가 형태를
	 * 보장하게 한다. 그래도 내용이 우리 기대와 맞는지는 부르는 쪽이 확인한다.
	 */
	@Override
	public String generate(String prompt, JsonNode responseSchema) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
				// 키를 질의 문자열이 아니라 헤더로 보낸다. URL 은 실패 메시지와 함께 그대로
				// 공개 실행 로그에 남는 자리다
				.header("x-goog-api-key", apiKey)
				.header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(120))
				.POST(HttpRequest.BodyPublishers.ofString(
						body(prompt, responseSchema), StandardCharsets.UTF_8))
				.build();

		HttpResponse<String> response = send(request);
		for (Duration wait : RETRY_WAITS) {
			if (!retryable(response.statusCode())) {
				break;
			}
			pause.accept(wait);
			response = send(request);
		}

		if (response.statusCode() != 200) {
			throw new IllegalStateException(
					"Gemini %s → %d %s".formatted(MODEL, response.statusCode(), response.body()));
		}
		return text(read(response.body()));
	}

	/**
	 * 다시 칠 응답. 429 는 빠진다 — 하루 한도는 PT 자정 리셋까지 풀리지 않고, 되친 요청도 한도를
	 * 깎는다. 타임아웃({@link UncheckedIOException})도 되치지 않는다: 요청 상한 120초에 세 번이면
	 * 잡 상한을 밀어 올리는데, 1차를 죽인 네 번은 전부 상태 코드로 온 503 이었다.
	 */
	static boolean retryable(int status) {
		return status >= 500;
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	private static void sleep(Duration wait) {
		try {
			Thread.sleep(wait);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
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
