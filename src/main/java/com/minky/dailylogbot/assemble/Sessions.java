package com.minky.dailylogbot.assemble;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 커밋 시각들을 실제로 앉아 있던 시간으로 접는다. */
public final class Sessions {

	/**
	 * 세션을 가르는 커밋 간격. study-log 가 제 기록을 커밋 이력에서 만들 때 쓴 값과 같다 —
	 * 같은 계정의 두 도구가 「한 번 앉은 것」을 서로 다르게 세면 통계가 갈린다.
	 */
	private static final Duration GAP = Duration.ofMinutes(90);

	private Sessions() {}

	/**
	 * 시간순 커밋 시각들을 앉아 있던 분으로 접는다.
	 *
	 * <p>세션 하나의 길이는 그 세션의 첫 커밋부터 마지막 커밋까지다. 첫 커밋 이전은 세지 않는다 —
	 * 커밋 전에도 작업이 있었겠지만 그 길이를 아는 근거가 없고, 지어낸 값을 더하는 순간 이
	 * 기록이 실측이기를 그만둔다. 커밋 하나짜리 세션이 0분인 것도 같은 이유다.
	 */
	public static int workedMinutes(List<Instant> times) {
		if (times.isEmpty()) {
			return 0;
		}

		int minutes = 0;
		Instant sessionStart = times.getFirst();

		for (int i = 1; i < times.size(); i++) {
			Instant previous = times.get(i - 1);
			if (Duration.between(previous, times.get(i)).compareTo(GAP) > 0) {
				minutes += (int) Duration.between(sessionStart, previous).toMinutes();
				sessionStart = times.get(i);
			}
		}

		return minutes + (int) Duration.between(sessionStart, times.getLast()).toMinutes();
	}
}
