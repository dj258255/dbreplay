package io.dbreplay.explore;

import java.util.List;

/**
 * 라운드 하나를 판정한다.
 *
 * <p>알람이 울리면 과부하다. 알람은 안 울렸지만 재생기가 계획을 못 지킨 문장이 많으면 왜곡이다. 왜곡된
 * 라운드는 대상에 그 배속의 부하가 실제로 걸리지 않았으므로 버텼다고 볼 수 없다.
 */
public final class RoundJudge {

	public enum State {
		/** 알람 없이 그 배속을 받아 냈다 */
		HELD,
		/** 알람이 울렸다 */
		OVERLOADED,
		/** 재생기가 그 배속을 내지 못했다. 대상이 버티는지 알 수 없다 */
		DISTORTED
	}

	/** @param reason 버틴 경우에는 null */
	public record Verdict(State state, String reason) {
	}

	private final List<Alarm> alarms;
	private final double maxDistortion;

	/** @param maxDistortion 재생기가 늦어 계획을 못 지킨 문장의 비율이 이보다 크면 왜곡으로 본다 */
	public RoundJudge(List<Alarm> alarms, double maxDistortion) {
		if (alarms.isEmpty()) {
			throw new IllegalArgumentException("과부하는 알람으로 판정한다. 알람이 하나는 있어야 한다");
		}
		this.alarms = List.copyOf(alarms);
		this.maxDistortion = maxDistortion;
	}

	public Verdict judge(RoundData round) throws Exception {
		for (Alarm alarm : alarms) {
			String reason = alarm.check(round);
			if (reason != null) {
				return new Verdict(State.OVERLOADED, reason);
			}
		}
		double distortion = round.summary().replayerLateRatio();
		if (distortion > maxDistortion) {
			return new Verdict(State.DISTORTED, String.format("재생기가 늦은 문장이 %.1f%%", distortion * 100));
		}
		return new Verdict(State.HELD, null);
	}
}
