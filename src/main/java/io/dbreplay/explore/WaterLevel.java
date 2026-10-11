package io.dbreplay.explore;

/**
 * 용량 수위. 지금 받는 부하가 버틸 수 있는 상한의 몇 할인지를 말한다.
 *
 * <p>메이투안 글의 정의를 그대로 쓴다. 수위 = 운영 QPS / 탐색으로 얻은 상한 QPS. 건강 구간의 기본값은
 * 30~50%이고, 50%는 "평소의 2배를 버텨야 한다"는 요구를 뒤집어 적은 값이다.
 *
 * @param currentQps 지금의 초당 문장 수
 * @param ceilingQps 탐색으로 얻은 상한
 * @param low        이보다 낮으면 과잉
 * @param high       이보다 높으면 부족
 */
public record WaterLevel(double currentQps, double ceilingQps, double low, double high) {

	public enum Verdict {
		/** 용량이 남는다 */
		SURPLUS,
		HEALTHY,
		/** 용량이 모자란다 */
		SHORT
	}

	/** 글의 기본 건강 구간 [30%, 50%]. 글은 이 구간을 클러스터별로 조정할 수 있다고 했다. */
	public static WaterLevel of(double currentQps, double ceilingQps) {
		return new WaterLevel(currentQps, ceilingQps, 0.30, 0.50);
	}

	public double level() {
		return currentQps / ceilingQps;
	}

	public Verdict verdict() {
		double level = level();
		return level < low ? Verdict.SURPLUS : level > high ? Verdict.SHORT : Verdict.HEALTHY;
	}

	/**
	 * 수위가 건강 구간의 위 끝에 오려면 용량이 지금의 몇 배여야 하는가.
	 * 1보다 크면 그만큼 늘려야 하고, 1보다 작으면 거기까지 줄여도 건강 구간 안이다.
	 */
	public double capacityFactor() {
		return level() / high;
	}
}
