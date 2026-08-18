package com.minky.dailylogbot.summarize;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 프롬프트 한 장을 스키마에 맞춘 JSON 으로 바꾸는 자리.
 *
 * <p>{@link Summarizer} 가 구현이 아니라 이 이름에 기대는 것은, 응답을 초안으로 옮기는 과정을
 * 실제 호출 없이 밟기 위해서다. 파싱과 검증이 붙어 있는 자리라 확인할 일이 잦은데 확인마다
 * 무료 티어 한도를 태우면 결국 확인하지 않게 된다.
 */
@FunctionalInterface
public interface SummaryModel {

	String generate(String prompt, JsonNode responseSchema);
}
