package com.minky.dailylogbot.assemble;

import com.minky.dailylogbot.collect.DayWindow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionsTest {

	private static final LocalDate DATE = LocalDate.of(2026, 8, 19);

	private static Instant kst(String hhmm) {
		return DATE.atTime(LocalTime.parse(hhmm)).atZone(DayWindow.SEOUL).toInstant();
	}

	@Test
	@DisplayName("간격이 임계값 이내인 커밋들은 한 세션 — 첫 커밋부터 마지막 커밋까지가 그 길이")
	void 한_세션은_처음부터_끝까지() {
		List<Instant> times = List.of(kst("08:20"), kst("08:24"), kst("08:43"));

		assertEquals(23, Sessions.workedMinutes(times));
	}

	@Test
	@DisplayName("임계값을 넘는 공백에서 세션이 갈린다 — 자리에 없던 시간은 세지 않는다")
	void 긴_공백에서_갈린다() {
		// 실측 2026-08-19 의 커밋 8건. 09:58 과 13:19 사이 201분은 앉아 있던 시간이 아니다
		List<Instant> times = List.of(
				kst("08:20"), kst("08:24"), kst("08:43"), kst("08:51"),
				kst("08:59"), kst("09:58"), kst("13:19"), kst("13:21"));

		assertEquals(98 + 2, Sessions.workedMinutes(times));
	}

	@Test
	@DisplayName("정확히 임계값인 간격은 아직 한 세션 — 갈리는 것은 그것을 넘을 때부터")
	void 경계는_아직_한_세션() {
		assertEquals(90, Sessions.workedMinutes(List.of(kst("08:00"), kst("09:30"))));
	}

	@Test
	@DisplayName("임계값을 1분 넘기면 갈린다 — 커밋 하나뿐인 세션은 0분")
	void 경계를_넘기면_갈린다() {
		assertEquals(0, Sessions.workedMinutes(List.of(kst("08:00"), kst("09:31"))));
	}

	@Test
	@DisplayName("커밋이 하나면 앉아 있던 시간을 알 수 없다 — 0분")
	void 커밋_하나는_0분() {
		assertEquals(0, Sessions.workedMinutes(List.of(kst("08:20"))));
	}

	@Test
	@DisplayName("커밋이 없으면 0분")
	void 빈_날은_0분() {
		assertEquals(0, Sessions.workedMinutes(List.of()));
	}

	@Test
	@DisplayName("긴 공백이 하루를 두 번 앉은 것으로 가른다 — 세션마다 첫 커밋과 마지막 커밋")
	void 세션_목록은_공백에서_갈린다() {
		// 실측 2026-08-21 의 커밋 시각. 16:18 과 19:14 사이 176분에서 갈린다
		List<Instant> times = List.of(
				kst("13:14"), kst("13:31"), kst("14:49"), kst("16:18"),
				kst("19:14"), kst("19:49"));

		assertEquals(
				List.of(new Sessions.Session(kst("13:14"), kst("16:18")),
						new Sessions.Session(kst("19:14"), kst("19:49"))),
				Sessions.split(times));
	}

	@Test
	@DisplayName("커밋이 없으면 세션도 없다")
	void 빈_날은_세션_없음() {
		assertEquals(List.of(), Sessions.split(List.of()));
	}
}
