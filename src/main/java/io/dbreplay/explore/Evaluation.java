package io.dbreplay.explore;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 용량 평가 한 번의 결과와, 그 평가가 어떤 조건에서 나왔는지의 기록.
 *
 * <p>메이투안 글은 평가 결과에 만료 시각을 두고, 업무 트래픽 모양, 하드웨어 사양, 버전과 파라미터,
 * 알람 임계값이 바뀌면 결과를 무효로 표시한다고 했다. {@link #invalidReasons}가 그 확인이다.
 *
 * @param evaluatedAt  평가한 시각 (ISO-8601)
 * @param validDays    며칠 동안 유효한가
 * @param profile      평가할 때 대상의 버전과 핵심 파라미터
 * @param alarms       평가할 때의 알람 설정
 * @param unit         재생 단위
 * @param traffic      평가에 쓴 트래픽의 모양
 * @param ceilingSpeed 버틴 가장 높은 배속
 * @param stoppedBy    위쪽 경계를 만든 판정. 경계를 못 봤으면 null
 * @param ceilingQps   상한 (초당 문장 수)
 * @param currentQps   수위를 낼 때 쓴 지금의 초당 문장 수
 * @param low          건강 구간의 아래 끝
 * @param high         건강 구간의 위 끝
 */
public record Evaluation(
		String evaluatedAt,
		int validDays,
		Map<String, String> profile,
		Map<String, String> alarms,
		String unit,
		TrafficModel traffic,
		double ceilingSpeed,
		String stoppedBy,
		double ceilingQps,
		double currentQps,
		double low,
		double high) {

	public WaterLevel level() {
		return new WaterLevel(currentQps, ceilingQps, low, high);
	}

	/**
	 * 이 평가를 지금도 믿어도 되는지 본다.
	 *
	 * @param currentTraffic      지금의 트래픽. 비교하지 않으려면 null
	 * @param maxTrafficDistance  트래픽 모양의 거리가 이보다 크면 모양이 바뀐 것으로 본다
	 * @return 무효인 이유들. 비어 있으면 유효하다
	 */
	public List<String> invalidReasons(Instant now, Map<String, String> currentProfile, Map<String, String> currentAlarms,
			TrafficModel currentTraffic, double maxTrafficDistance) {
		List<String> reasons = new ArrayList<>();
		Instant expires = Instant.parse(evaluatedAt).plus(validDays, ChronoUnit.DAYS);
		if (now.isAfter(expires)) {
			reasons.add("유효 기간이 지났다 (" + expires + "까지)");
		}
		String changed = differences(profile, currentProfile);
		if (!changed.isEmpty()) {
			reasons.add("버전이나 파라미터가 바뀌었다: " + changed);
		}
		changed = differences(alarms, currentAlarms);
		if (!changed.isEmpty()) {
			reasons.add("알람 기준이 바뀌었다: " + changed);
		}
		if (currentTraffic != null) {
			double distance = traffic.distance(currentTraffic);
			if (distance > maxTrafficDistance) {
				reasons.add(String.format("트래픽 모양이 바뀌었다 (거리 %.2f)", distance));
			}
		}
		return reasons;
	}

	private static String differences(Map<String, String> before, Map<String, String> now) {
		List<String> diffs = new ArrayList<>();
		TreeSet<String> keys = new TreeSet<>(before.keySet());
		keys.addAll(now.keySet());
		for (String key : keys) {
			if (!Objects.equals(before.get(key), now.get(key))) {
				diffs.add(key + " " + before.get(key) + " → " + now.get(key));
			}
		}
		return String.join(", ", diffs);
	}
}
