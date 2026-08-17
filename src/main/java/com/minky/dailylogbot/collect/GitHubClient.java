package com.minky.dailylogbot.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** GitHub REST 를 JSON 트리로만 돌려주는 얇은 통로. 수집 규칙은 {@link ActivityCollector} 몫이다. */
public final class GitHubClient {

	private static final String BASE = "https://api.github.com";
	private static final int PAGE_SIZE = 100;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private final ObjectMapper mapper = new ObjectMapper();
	private final String token;

	public GitHubClient(String token) {
		if (token == null || token.isBlank()) {
			throw new IllegalArgumentException("GH_PAT 없음 — 계정 전체 조회에 repo 범위 토큰이 필요하다");
		}
		this.token = token;
	}

	public JsonNode get(String path, Map<String, String> query) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path + encode(query)))
				.header("Authorization", "Bearer " + token)
				.header("Accept", "application/vnd.github+json")
				.header("X-GitHub-Api-Version", "2022-11-28")
				.header("User-Agent", "daily-log-bot")
				.timeout(Duration.ofSeconds(30))
				.GET()
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
					"GitHub %s → %d %s".formatted(path, response.statusCode(), response.body()));
		}
		try {
			return mapper.readTree(response.body());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 배열 응답을 끝까지 넘긴다. 리포 목록은 기본 30건이라 계정 전체가 한 장에 들어오지 않는다. */
	public List<JsonNode> getAllPages(String path, Map<String, String> query) {
		List<JsonNode> all = new ArrayList<>();
		for (int page = 1; ; page++) {
			Map<String, String> paged = new java.util.LinkedHashMap<>(query);
			paged.put("per_page", String.valueOf(PAGE_SIZE));
			paged.put("page", String.valueOf(page));

			JsonNode body = get(path, paged);
			body.forEach(all::add);
			if (body.size() < PAGE_SIZE) {
				return all;
			}
		}
	}

	private static String encode(Map<String, String> query) {
		if (query.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder("?");
		query.forEach((k, v) -> sb.append(sb.length() > 1 ? "&" : "")
				.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
				.append('=')
				.append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
		return sb.toString();
	}
}
