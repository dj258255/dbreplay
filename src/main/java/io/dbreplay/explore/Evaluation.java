package io.dbreplay.explore;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 * @param ceilingSpeed 버틴 가장 높은 배속. 되풀이했으면 가장 낮은 상한
 * @param stoppedBy    위쪽 경계를 만든 판정. 경계를 못 봤으면 null. 되풀이했으면 가장 낮은 상한을 낸 탐색의 판정
 * @param ceilingQps   상한 (초당 문장 수). 되풀이했으면 가장 낮은 상한으로 계산한 값
 * @param currentQps   수위를 낼 때 쓴 지금의 초당 문장 수
 * @param low          건강 구간의 아래 끝
 * @param high         건강 구간의 위 끝
 * @param ceilingSpeeds 되풀이한 탐색마다의 상한. 되풀이하지 않았으면 null. 파일에 이 항목이 없으면 되풀이하지 않은 것이다
 * @param explorations 탐색마다의 라운드들. 되풀이하지 않았으면 탐색 하나다. 남기지 않은 예전 파일이면 null
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
		double high,
		@JsonInclude(JsonInclude.Include.NON_NULL) List<Double> ceilingSpeeds,
		@JsonInclude(JsonInclude.Include.NON_NULL) List<List<RoundLog>> explorations) {

	/**
	 * 탐색의 한 라운드. 리포트의 상승 과정에 쓴다.
	 *
	 * @param speed      그 라운드의 배속
	 * @param verdict    판정. HELD, OVERLOADED, DISTORTED
	 * @param reason     과부하나 왜곡으로 판정한 이유. 버텼으면 null
	 * @param distortion 재생기가 늦어 계획을 못 지킨 문장의 비율
	 * @param metrics    지표 이름별 [평균, 최대]
	 */
	public record RoundLog(double speed, String verdict, String reason, double distortion, Map<String, List<Double>> metrics) {

		/** 라운드에서 만든다. 지표는 이름 순으로 [평균, 최대]를 담는다. */
		public static RoundLog of(Explorer.Round round) {
			Map<String, List<Double>> metrics = new TreeMap<>();
			round.data().metrics().keySet()
					.forEach(name -> metrics.put(name, List.of(round.data().mean(name), round.data().max(name))));
			return new RoundLog(round.speed(), round.verdict().state().name(), round.verdict().reason(),
					round.data().distortion(), metrics);
		}
	}

	/** 되풀이하지 않은 평가. 예전 호출과 파일 형식을 그대로 둔다. */
	public Evaluation(String evaluatedAt, int validDays, Map<String, String> profile, Map<String, String> alarms,
			String unit, TrafficModel traffic, double ceilingSpeed, String stoppedBy, double ceilingQps, double currentQps,
			double low, double high) {
		this(evaluatedAt, validDays, profile, alarms, unit, traffic, ceilingSpeed, stoppedBy, ceilingQps, currentQps, low,
				high, null, null);
	}

	/** 되풀이한 상한 목록은 있지만 라운드 기록은 없는 평가. */
	public Evaluation(String evaluatedAt, int validDays, Map<String, String> profile, Map<String, String> alarms,
			String unit, TrafficModel traffic, double ceilingSpeed, String stoppedBy, double ceilingQps, double currentQps,
			double low, double high, List<Double> ceilingSpeeds) {
		this(evaluatedAt, validDays, profile, alarms, unit, traffic, ceilingSpeed, stoppedBy, ceilingQps, currentQps, low,
				high, ceilingSpeeds, null);
	}

	public WaterLevel level() {
		return new WaterLevel(currentQps, ceilingQps, low, high);
	}

	/**
	 * 되풀이한 탐색이 있으면 상한들을 범위로 묶어 돌려준다. 없으면 null.
	 *
	 * <p>allConfirmed는 false로 둔다. 그 값은 파일에 남기지 않으므로, 파일만 보고는 상한이 알람으로 확인된
	 * 값인지 알 수 없다. 평가를 읽어 보여 주는 데는 쓰지 않아 파일에 남기지 않았다.
	 *
	 * <p>상한 목록이 비어 있으면(손으로 고친 파일 같은 경우) 범위가 없는 것으로 본다. 빈 목록으로 범위를
	 * 만들면 {@code min()}이 예외로 죽는다.
	 */
	public CeilingRange ceilingRange() {
		if (ceilingSpeeds == null || ceilingSpeeds.isEmpty()) {
			return null;
		}
		return new CeilingRange(ceilingSpeeds, stoppedBy == null ? null : RoundJudge.State.valueOf(stoppedBy), false);
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
