package com.minky.dailylogbot.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StudyLogClientTest {

	/** 스프링이 렌더하는 hidden 필드에서 토큰을 뽑는다 — name 다음 value 순서다. */
	@Test
	void csrf_토큰을_렌더된_폼에서_뽑는다() {
		String html = """
				<form method="post" action="/login">
				<input type="hidden" name="_csrf" value="abc-123-token"/>
				<input name="username"></form>""";

		assertEquals("abc-123-token", StudyLogClient.csrfToken(html));
	}

	/** 토큰이 없으면 던진다 — 없이 POST 하면 403 만 돌아와 원인이 흐려진다. */
	@Test
	void csrf_없으면_던진다() {
		assertThrows(IllegalStateException.class,
				() -> StudyLogClient.csrfToken("<form></form>"));
	}

	/** _csrf 필드와 files 파일 하나짜리 본문. 파일명 · 마크다운 · 경계가 그대로 실린다. */
	@Test
	void multipart_는_csrf_와_파일을_담는다() {
		byte[] bytes =
				StudyLogClient.multipart("BOUND", "tok-9", "2026-08-18.md", "---\ntitle: x\n---\n본문");
		String body = new String(bytes, StandardCharsets.UTF_8);

		assertTrue(body.contains("name=\"_csrf\""), body);
		assertTrue(body.contains("tok-9"), body);
		assertTrue(body.contains("name=\"files\"; filename=\"2026-08-18.md\""), body);
		assertTrue(body.contains("본문"), body);
		assertTrue(body.startsWith("--BOUND\r\n"), body);
		assertTrue(body.endsWith("\r\n--BOUND--\r\n"), body);
	}
}
