package io.dbreplay.explore;

import java.util.List;
import java.util.Map;

/**
 * 위탁해 둔 대상의 목록. 메이투안의 시스템은 트래픽 모양이 안정적인 핵심 클러스터를 등록해 두고, 평가가 만료되거나
 * 무효가 되면 다시 평가한다.
 *
 * <p>{@code manage}는 한 번 훑고 끝난다. {@code --every}를 주면 떠 있으면서 주기마다 훑는다(#87).
 *
 * @param targets 대상들
 */
public record ManagedTargets(List<Target> targets) {

	/**
	 * @param name       대상의 이름. 출력에 쓴다
	 * @param evaluation 이 대상의 평가를 두는 파일
	 * @param options    {@code explore}에 주는 옵션을 그대로 적는다. 예: {@code "--trace": "prod.trace.jsonl"}
	 * @param traces     등록 규칙을 볼 최근 트래픽 파일들. 오래된 것부터. 없으면 규칙을 보지 않고 늘 등록된 것으로 본다
	 */
	public record Target(String name, String evaluation, Map<String, String> options, List<String> traces) {

		public Target {
			if (name == null || evaluation == null || options == null) {
				throw new IllegalArgumentException("대상에는 name, evaluation, options가 있어야 한다");
			}
			traces = traces == null ? List.of() : List.copyOf(traces);
		}

		public Target(String name, String evaluation, Map<String, String> options) {
			this(name, evaluation, options, null);
		}
	}

	/** 대상 하나에 대해 이번에 한 일. */
	public enum Action {
		/** 평가가 유효해서 건드리지 않았다 */
		KEPT,
		/** 평가한 적이 없어 평가했다 */
		EVALUATED,
		/** 평가가 만료됐거나 무효여서 다시 평가했다 */
		REEVALUATED,
		/** 평가하다 실패했다 */
		FAILED,
		/** 등록 규칙에 들지 않아 평가하지 않았다(#87) */
		NOT_REGISTERED
	}

	/**
	 * 트래픽 모양이 안정적이고 부하가 있는 대상만 평가한다(원문 대조 48, #87). 글은 "트래픽 모양이 안정적인 핵심 클러스터"를 규칙으로
	 * 등록한다고만 하고 기준은 없다. 작업 세션이 고른 기준이다: 최근 트래픽 파일의 이웃한 둘 사이의 모양 거리가 모두
	 * {@code maxDistance} 안이고, 가장 최근 것의 초당 문장 수가 {@code minQps} 이상이다. 트래픽 파일이 둘 미만이면 안정적인지
	 * 알 수 없어 들지 않는다.
	 *
	 * @param models 최근 트래픽의 모양. 오래된 것부터
	 * @return 들지 않는 이유. 들면 null
	 */
	public static String notRegisteredBecause(List<TrafficModel> models, double maxDistance, double minQps) {
		if (models.size() < 2) {
			return "트래픽 파일이 둘 미만이라 모양이 안정적인지 알 수 없다";
		}
		for (int i = 1; i < models.size(); i++) {
			double distance = models.get(i - 1).distance(models.get(i));
			if (distance > maxDistance) {
				return String.format("트래픽 모양이 안정적이지 않다 (%d번째와 %d번째의 거리 %.2f)", i, i + 1, distance);
			}
		}
		double qps = models.getLast().qps();
		if (qps < minQps) {
			return String.format("부하가 기준보다 작다 (초당 %.0f문장)", qps);
		}
		return null;
	}
}
