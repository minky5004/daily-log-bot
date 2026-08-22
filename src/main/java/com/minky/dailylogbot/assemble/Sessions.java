package com.minky.dailylogbot.assemble;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
		int minutes = 0;
		for (Session session : split(times)) {
			minutes += (int) Duration.between(session.start(), session.end()).toMinutes();
		}
		return minutes;
	}

	/** 한 번 앉은 구간. 첫 커밋과 마지막 커밋이다. */
	public record Session(Instant start, Instant end) {}

	/**
	 * 시간순 커밋 시각들을 앉아 있던 구간들로 가른다. {@link #workedMinutes} 가 세는 것이
	 * 정확히 이 구간들의 길이 합이라, 둘이 같은 자리에서 갈린다.
	 */
	public static List<Session> split(List<Instant> times) {
		if (times.isEmpty()) {
			return List.of();
		}

		List<Session> sessions = new ArrayList<>();
		Instant sessionStart = times.getFirst();

		for (int i = 1; i < times.size(); i++) {
			Instant previous = times.get(i - 1);
			if (Duration.between(previous, times.get(i)).compareTo(GAP) > 0) {
				sessions.add(new Session(sessionStart, previous));
				sessionStart = times.get(i);
			}
		}

		sessions.add(new Session(sessionStart, times.getLast()));
		return List.copyOf(sessions);
	}
}
