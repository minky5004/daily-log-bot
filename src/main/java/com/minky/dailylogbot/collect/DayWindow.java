package com.minky.dailylogbot.collect;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * KST 하루를 GitHub 이 이해하는 UTC 구간으로 옮긴 것.
 *
 * <p>대상이 "오늘" 이 아니라 "어제" 인 것은 액션 예약 지연 때문이다. 15:00 UTC 예약이 몇 분
 * 밀려도 이미 닫힌 하루를 보는 한 결과가 흔들리지 않는다.
 */
public record DayWindow(LocalDate date, Instant from, Instant toExclusive) {

	public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final DateTimeFormatter API = DateTimeFormatter.ISO_INSTANT;

	public static DayWindow of(LocalDate date) {
		return new DayWindow(
				date,
				date.atStartOfDay(SEOUL).toInstant(),
				date.plusDays(1).atStartOfDay(SEOUL).toInstant());
	}

	public static DayWindow yesterday(Clock clock) {
		return of(LocalDate.now(clock.withZone(SEOUL)).minusDays(1));
	}

	public String since() {
		return API.format(from);
	}

	/**
	 * GitHub 의 {@code until} 은 경계를 포함하므로 1초 앞에서 끊는다. 열린 끝을 그대로 넘기면
	 * 자정 정각 커밋이 이틀에 걸쳐 두 번 잡힌다.
	 */
	public String until() {
		return API.format(toExclusive.minusSeconds(1));
	}
}
