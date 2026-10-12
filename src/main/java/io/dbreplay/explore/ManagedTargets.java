package io.dbreplay.explore;

import java.util.List;
import java.util.Map;

/**
 * 위탁해 둔 대상의 목록. 메이투안의 시스템은 트래픽 모양이 안정적인 핵심 클러스터를 등록해 두고, 평가가 만료되거나
 * 무효가 되면 다시 평가한다.
 *
 * <p>이 도구는 떠 있는 프로세스가 아니라서 "자동"은 한 번 훑는 명령까지다. 주기 실행은 cron 같은 바깥의 도구가 한다.
 *
 * @param targets 대상들
 */
public record ManagedTargets(List<Target> targets) {

	/**
	 * @param name       대상의 이름. 출력에 쓴다
	 * @param evaluation 이 대상의 평가를 두는 파일
	 * @param options    {@code explore}에 주는 옵션을 그대로 적는다. 예: {@code "--trace": "prod.trace.jsonl"}
	 */
	public record Target(String name, String evaluation, Map<String, String> options) {

		public Target {
			if (name == null || evaluation == null || options == null) {
				throw new IllegalArgumentException("대상에는 name, evaluation, options가 있어야 한다");
			}
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
		FAILED
	}
}
