package com.minky.dailylogbot.summarize;

/**
 * 요약이 만든 TIL 초안. 조립 사이클이 프론트매터의 {@code summary} 와 본문으로 나눠 담는다.
 *
 * <p>둘을 한 번에 받는 것은 같은 하루를 두 번 부르지 않기 위해서다 — 나눠 부르면 요약과 본문이
 * 서로 다른 하루를 가리킬 수 있고, 무료 티어 호출도 두 배가 된다.
 */
public record TilDraft(String summary, String body) {}
