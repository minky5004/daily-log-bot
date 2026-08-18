package com.minky.dailylogbot.upload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * study-log 폼 로그인과 {@code /import} 로만 드나드는 얇은 통로. 언제 건너뛸지 · 결과를 어떻게
 * 읽을지는 {@link Uploader} 몫이다.
 *
 * <p>세션 쿠키는 {@link CookieManager} 가 요청마다 자동으로 싣는다 — 로그인 성공 순간 스프링이
 * 세션 고정 방어로 {@code JSESSIONID} 를 새로 발급하는데, 쿠키 항아리가 그 교체를 따라간다.
 * {@code _csrf} 는 템플릿 소스에 없다 — {@code th:action} 이 렌더 시점에 hidden 필드로 심으므로
 * 화면을 받아 거기서 뽑는다.
 */
public final class StudyLogClient implements StudyLog {

	/** 렌더된 폼이 심는 CSRF hidden 필드. 스프링은 {@code name} 다음에 {@code value} 를 낸다. */
	private static final Pattern CSRF =
			Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

	/**
	 * 요청 하나의 상한. study-log 는 Render 무료 티어라 유휴 뒤 첫 요청이 앱을 깨우고, 그 콜드
	 * 스타트가 이 상한 하나를 넘기기도 한다(실측 — 120초 상한에서 첫 조회가 타임아웃). 그래서
	 * 상한만 키우지 않고 첫 접촉 GET 을 되친다 — {@link #GET_ATTEMPTS} 참고.
	 */
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

	/**
	 * GET 재시도 횟수. 콜드 스타트는 상한이 얼마든 가끔 넘기므로 상한을 키우는 대신 되친다 —
	 * 되치는 사이 앱이 깨어나 다음 시도는 곧 응답한다. GET 만 되친다: 임포트 POST 가 서버에 닿은
	 * 뒤 응답만 늦으면 되쳤을 때 기록이 둘이 된다 — 이 사이클이 막으려는 바로 그 중복이다.
	 * 상한 120초 × 3 = 최악 6분으로 잡 상한 15분 안이다.
	 */
	private static final int GET_ATTEMPTS = 3;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			// 로그인 성공은 302 라 자동으로 좇으면 상태를 볼 수 없다. 다른 호출은 200 직행이다
			.followRedirects(HttpClient.Redirect.NEVER)
			.cookieHandler(new CookieManager())
			.build();

	private final String baseUrl;
	private final String username;
	private final String password;

	public StudyLogClient(String baseUrl, String username, String password) {
		if (baseUrl == null || baseUrl.isBlank()) {
			throw new IllegalArgumentException("STUDYLOG_BASE_URL 없음 — 조회·업로드 대상이 필요하다");
		}
		if (username == null || username.isBlank() || password == null || password.isBlank()) {
			throw new IllegalArgumentException("STUDYLOG_USERNAME · STUDYLOG_PASSWORD 없음 — 업로드에 필요하다");
		}
		// 끝의 슬래시가 있으면 경로가 이중 슬래시가 된다
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.username = username;
		this.password = password;
	}

	@Override
	public String search(LocalDate from, LocalDate to, String keyword) {
		String query = "?from=%s&to=%s&keyword=%s"
				.formatted(from, to, URLEncoder.encode(keyword, StandardCharsets.UTF_8));
		HttpResponse<String> response = getRetrying(
				HttpRequest.newBuilder(URI.create(baseUrl + "/logs" + query)).GET(), "GET /logs");
		require(response, 200, "GET /logs");
		return response.body();
	}

	@Override
	public void login() {
		HttpResponse<String> form =
				getRetrying(HttpRequest.newBuilder(URI.create(baseUrl + "/login")).GET(), "GET /login");
		require(form, 200, "GET /login");
		String csrf = csrfToken(form.body());

		String body = "username=%s&password=%s&_csrf=%s".formatted(
				URLEncoder.encode(username, StandardCharsets.UTF_8),
				URLEncoder.encode(password, StandardCharsets.UTF_8),
				URLEncoder.encode(csrf, StandardCharsets.UTF_8));
		HttpResponse<String> result = sendOnce(
				HttpRequest.newBuilder(URI.create(baseUrl + "/login"))
						.header("Content-Type", "application/x-www-form-urlencoded")
						.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)),
				"POST /login");

		// 성공은 302 로 성공 URL 로, 실패는 302 로 /login?error 로 간다. 사유를 아이디·비밀번호로
		// 갈라 말하지 않는 화면이라 여기서도 갈리는 것은 성공 여부뿐이다
		String location = result.headers().firstValue("Location").orElse("");
		if (result.statusCode() != 302 || location.contains("error")) {
			throw new IllegalStateException(
					"study-log 로그인 실패 — %d %s".formatted(result.statusCode(), location));
		}
	}

	@Override
	public String importMarkdown(String fileName, String markdown) {
		HttpResponse<String> form =
				getRetrying(HttpRequest.newBuilder(URI.create(baseUrl + "/import")).GET(), "GET /import");
		require(form, 200, "GET /import");
		String csrf = csrfToken(form.body());

		String boundary = "----dailylogbot" + Long.toHexString(System.nanoTime());
		byte[] payload = multipart(boundary, csrf, fileName, markdown);
		HttpResponse<String> result = sendOnce(
				HttpRequest.newBuilder(URI.create(baseUrl + "/import"))
						.header("Content-Type", "multipart/form-data; boundary=" + boundary)
						.POST(HttpRequest.BodyPublishers.ofByteArray(payload)),
				"POST /import");
		require(result, 200, "POST /import");
		return result.body();
	}

	/**
	 * 렌더된 폼에서 CSRF 토큰을 뽑는다. 없으면 던진다 — CSRF 가 꺼졌거나 화면 계약이 바뀐 것이라
	 * 토큰 없이 POST 를 보내면 403 만 돌아온다.
	 */
	static String csrfToken(String html) {
		Matcher matcher = CSRF.matcher(html);
		if (!matcher.find()) {
			throw new IllegalStateException("study-log 화면에서 _csrf 를 찾지 못함");
		}
		return matcher.group(1);
	}

	/**
	 * {@code _csrf} 필드 하나와 {@code files} 파일 하나짜리 multipart 본문. 서버가 파일명이
	 * {@code .md} 로 끝나지 않으면 ZIP 으로 읽으므로 파일명은 부르는 쪽이 맞춰 준다.
	 */
	static byte[] multipart(String boundary, String csrf, String fileName, String markdown) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StringBuilder head = new StringBuilder();
		head.append("--").append(boundary).append("\r\n")
				.append("Content-Disposition: form-data; name=\"_csrf\"\r\n\r\n")
				.append(csrf).append("\r\n")
				.append("--").append(boundary).append("\r\n")
				.append("Content-Disposition: form-data; name=\"files\"; filename=\"")
				.append(fileName).append("\"\r\n")
				.append("Content-Type: text/markdown; charset=UTF-8\r\n\r\n");
		out.writeBytes(head.toString().getBytes(StandardCharsets.UTF_8));
		out.writeBytes(markdown.getBytes(StandardCharsets.UTF_8));
		out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return out.toByteArray();
	}

	private HttpResponse<String> sendOnce(HttpRequest.Builder request, String label) {
		try {
			return http.send(
					request.timeout(REQUEST_TIMEOUT).header("User-Agent", "daily-log-bot").build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(label + " 중단", e);
		}
	}

	/**
	 * 콜드 스타트를 넘기려 GET 을 되친다. 타임아웃·연결 실패({@link UncheckedIOException})는 앱이
	 * 아직 깨는 중이라는 신호라 다시 치고, 그 밖의 실패(잘못된 상태 코드 등)는 그대로 올린다.
	 */
	private HttpResponse<String> getRetrying(HttpRequest.Builder request, String label) {
		UncheckedIOException last = null;
		for (int attempt = 1; attempt <= GET_ATTEMPTS; attempt++) {
			try {
				return sendOnce(request, label);
			} catch (UncheckedIOException e) {
				last = e;
			}
		}
		throw new IllegalStateException(
				"%s — %d회 모두 응답 없음 (Render 콜드 스타트)".formatted(label, GET_ATTEMPTS), last);
	}

	/** 응답 본문은 붙이지 않는다 — 실패 화면이 커 로그를 덮고, 비밀번호를 실은 요청의 되울림이 섞일 수 있다. */
	private static void require(HttpResponse<String> response, int expected, String label) {
		if (response.statusCode() != expected) {
			throw new IllegalStateException(
					"study-log %s → %d (기대 %d)".formatted(label, response.statusCode(), expected));
		}
	}
}
