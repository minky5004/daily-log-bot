package com.minky.dailylogbot.collect;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

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

	/**
	 * 손으로 준 날짜의 하루. 비어 있으면 {@link #yesterday} — 예약 발화에는 입력이 없다.
	 *
	 * <p>KST 오늘 이후는 거부한다. 덜 끝난 하루를 올리면 그날 밤 예약이 제목으로 "이미 있음" 을
	 * 판정해 건너뛰어, 올린 뒤의 커밋이 영영 빠진다. 어제만 되던 때는 시한(이튿날 자정)을 넘긴
	 * 결번을 되살릴 길이 없었다 — 9/25 가 그 자리다.
	 */
	public static DayWindow target(String requested, Clock clock) {
		if (requested == null || requested.isBlank()) {
			return yesterday(clock);
		}
		LocalDate date;
		try {
			date = LocalDate.parse(requested.strip());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("날짜 형식은 YYYY-MM-DD — 받은 값 " + requested, e);
		}
		LocalDate today = LocalDate.now(clock.withZone(SEOUL));
		if (!date.isBefore(today)) {
			throw new IllegalArgumentException(
					"KST 오늘(%s) 이전 날짜만 — 받은 값 %s · 덜 끝난 하루는 그날 밤 예약이 건너뛴다".formatted(today, date));
		}
		return of(date);
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
