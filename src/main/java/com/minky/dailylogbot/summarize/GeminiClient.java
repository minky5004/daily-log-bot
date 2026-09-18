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

	/**
	 * {@link #MODEL} 이 재시도 끝까지 막혔을 때 한 번 더 던질 모델.
	 *
	 * <p>재시도도 백업 발화도 같은 모델을 두드린다. 9/17 은 30초 · 60초 재시도 셋이 다 503 이었다 —
	 * 과부하가 분 단위를 넘기면 바꿀 수 있는 것은 모델뿐이다.
	 *
	 * <p>위가 아니라 아래 세대로 넘어간다. 과부하는 새 세대에 몰린다 — 9/18 11:38~11:45 UTC 1분
	 * 간격 세 번에서 3.7 · 3.8 은 전부 503 · 3.5 는 셋 중 둘 · 2.5 는 셋 다 200 이었고, ai-cards-news
	 * 의 3.7 은 9/14~9/17 여덟 실행 중 여섯이 503 이었다. lite 도 셋 다 열려 있었지만 문체 규칙을
	 * 지켜 쓸 글이라 full flash 에서 고른다. 한도는 모델마다 따로 서서 1차 · 백업이 한 번씩
	 * 넘어와도 2/20 이다.
	 *
	 * <p>폴백은 한 번만 친다. 1차가 그 한 번까지 잃으면 한 시간 뒤 백업이 두 모델을 처음부터 다시
	 * 두드린다.
	 */
	private static final String FALLBACK_MODEL = "gemini-2.5-flash";

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
	private final String base;
	private final Consumer<Duration> pause;

	public GeminiClient(String apiKey) {
		this(apiKey, BASE, GeminiClient::sleep);
	}

	/** 주소와 대기를 바꿔 끼우는 자리. 테스트가 가짜 서버를 두고 대기는 기록만 한다. */
	GeminiClient(String apiKey, String base, Consumer<Duration> pause) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalArgumentException("GEMINI_API_KEY 없음 — 요약에 필요하다");
		}
		this.apiKey = apiKey;
		this.base = base;
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
		String body = body(prompt, responseSchema);
		HttpResponse<String> response = send(request(MODEL, body));
		for (Duration wait : RETRY_WAITS) {
			if (!retryable(response.statusCode())) {
				break;
			}
			pause.accept(wait);
			response = send(request(MODEL, body));
		}
		if (response.statusCode() == 200) {
			return text(read(response.body()));
		}

		String failure = failure(MODEL, response);
		if (!fallsBack(response.statusCode())) {
			throw new IllegalStateException(failure);
		}
		// 성공한 날에도 남긴다 — 이 줄이 없으면 그날 노트를 어느 모델이 썼는지 로그로 갈리지 않는다
		System.out.printf("%s → %d · %s 로 넘어감%n", MODEL, response.statusCode(), FALLBACK_MODEL);
		HttpResponse<String> fallback = send(request(FALLBACK_MODEL, body));
		if (fallback.statusCode() != 200) {
			// 첫 모델의 사유도 싣는다 — 폴백 쪽 오류만 남으면 왜 넘어갔는지가 로그에서 사라진다
			throw new IllegalStateException(failure + "\n" + failure(FALLBACK_MODEL, fallback));
		}
		return text(read(fallback.body()));
	}

	private HttpRequest request(String model, String body) {
		return HttpRequest.newBuilder(URI.create(base + model + ":generateContent"))
				// 키를 질의 문자열이 아니라 헤더로 보낸다. URL 은 실패 메시지와 함께 그대로
				// 공개 실행 로그에 남는 자리다
				.header("x-goog-api-key", apiKey)
				.header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(120))
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
	}

	private static String failure(String model, HttpResponse<String> response) {
		return "Gemini %s → %d %s".formatted(model, response.statusCode(), response.body());
	}

	/**
	 * 다시 칠 응답. 429 는 빠진다 — 하루 한도는 PT 자정 리셋까지 풀리지 않고, 되친 요청도 한도를
	 * 깎는다. 타임아웃({@link UncheckedIOException})도 되치지 않는다: 요청 상한 120초에 세 번이면
	 * 잡 상한을 밀어 올리는데, 1차를 죽인 네 번은 전부 상태 코드로 온 503 이었다.
	 */
	static boolean retryable(int status) {
		return status >= 500;
	}

	/**
	 * 다른 모델로 넘어갈 실패. 되친 끝의 5xx 에 429 가 더해진다 — 같은 모델로는 되치지 않는 429 도
	 * 한도가 모델마다 따로 서서 다른 모델에는 남아 있다. 400 같은 요청 쪽 실패는 모델을 바꿔도 같다.
	 */
	static boolean fallsBack(int status) {
		return retryable(status) || status == 429;
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
