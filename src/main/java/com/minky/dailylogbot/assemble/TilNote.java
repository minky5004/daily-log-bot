package com.minky.dailylogbot.assemble;

import java.time.LocalDate;

/**
 * 업로드할 마크다운 한 장.
 *
 * <p>{@code title} 과 {@code date} 를 본문 밖에 따로 들고 있는 것은 둘이 study-log 의 중복 판정
 * 키이기 때문이다. 프론트매터에서 다시 긁어내게 두면 제목을 짓는 규칙이 두 곳에 생기고, 한쪽만
 * 바뀌는 날 같은 하루가 기록 둘이 된다.
 */
public record TilNote(String title, LocalDate date, String markdown) {}
