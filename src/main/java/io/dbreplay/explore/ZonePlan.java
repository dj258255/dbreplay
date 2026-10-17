package io.dbreplay.explore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 권하는 읽기 노드 수를 존(IDC)에 나눈다. 글은 모자라면 늘릴 노드 수와 최적의 IDC 분포를, 남으면 재해 복구 요건을 지키며
 * 줄일 수를 낸다(원문 대조 53a, 53b, #85). 최적이 무엇인지는 글에 없다. 작업 세션이 고른 규칙이고 ORIGIN.md 2절에 적었다.
 *
 * <ul>
 * <li>늘릴 때는 노드가 가장 적은 존부터 하나씩 채운다. 존 사이의 차이를 가장 작게 하려는 것이다.
 * <li>줄일 때는 가장 많은 존부터 하나씩 뺀다. 존마다의 최소는 지킨다.
 * <li>같은 수의 존이 여럿이면 사용자가 적은 순서를 따른다. 결과가 실행마다 같게 하려는 것이다.
 * </ul>
 *
 * <p>재해 복구 점검은 노드가 가장 많은 존 하나가 통째로 빠졌을 때 남은 노드의 읽기 수위다. 권고와 같은 가정(읽기 수위는
 * 노드 수에 반비례한다)을 쓴다.
 *
 * @param before      지금 존마다의 노드 수
 * @param after       권하는 존마다의 노드 수
 * @param lostZone    빠졌다고 볼 존(권고 뒤 노드가 가장 많은 존)
 * @param levelIfLost 그 존이 빠졌을 때 남은 노드의 읽기 수위. 존이 하나뿐이면 무한대
 */
public record ZonePlan(Map<String, Integer> before, Map<String, Integer> after, String lostZone, double levelIfLost) {

	/**
	 * @param zones      지금 존마다의 노드 수. 적은 순서를 지킨다
	 * @param total      권하는 전체 노드 수({@link ScaleAdvice#recommendedReadNodes()})
	 * @param minPerZone 존마다 지켜야 하는 최소 노드 수
	 * @param readLevel  권고에 쓴 지금의 읽기 수위
	 */
	public static ZonePlan of(Map<String, Integer> zones, int total, int minPerZone, double readLevel) {
		if (zones.isEmpty()) {
			throw new IllegalArgumentException("존이 하나도 없다");
		}
		if (minPerZone < 0) {
			throw new IllegalArgumentException("존마다의 최소 노드 수는 0 이상이어야 한다");
		}
		Map<String, Integer> after = new LinkedHashMap<>(zones);
		after.replaceAll((zone, n) -> Math.max(n, minPerZone));
		while (sum(after) < total) {
			String fewest = null;
			for (Map.Entry<String, Integer> e : after.entrySet()) {
				if (fewest == null || e.getValue() < after.get(fewest)) {
					fewest = e.getKey();
				}
			}
			after.merge(fewest, 1, Integer::sum);
		}
		while (sum(after) > total) {
			String most = null;
			for (Map.Entry<String, Integer> e : after.entrySet()) {
				if (e.getValue() > minPerZone && (most == null || e.getValue() > after.get(most))) {
					most = e.getKey();
				}
			}
			if (most == null) {
				break; // 모든 존이 최소에 닿았다. 더 줄이지 않는다
			}
			after.merge(most, -1, Integer::sum);
		}
		String lost = null;
		for (Map.Entry<String, Integer> e : after.entrySet()) {
			if (lost == null || e.getValue() > after.get(lost)) {
				lost = e.getKey();
			}
		}
		int before = sum(zones);
		int remaining = sum(after) - after.get(lost);
		double levelIfLost = remaining <= 0 ? Double.POSITIVE_INFINITY : readLevel * before / remaining;
		return new ZonePlan(Collections.unmodifiableMap(new LinkedHashMap<>(zones)), Collections.unmodifiableMap(after), lost,
				levelIfLost);
	}

	/** {@code a=2,b=1}. 존 이름과 노드 수. 적은 순서를 지킨다. */
	public static Map<String, Integer> parse(String text) {
		Map<String, Integer> zones = new LinkedHashMap<>();
		for (String part : text.split(",")) {
			String[] kv = part.strip().split("=", 2);
			if (kv.length != 2 || kv[0].isBlank()) {
				throw new IllegalArgumentException("--zones는 a=2,b=1처럼 준다");
			}
			int n;
			try {
				n = Integer.parseInt(kv[1].strip());
			}
			catch (NumberFormatException e) {
				throw new IllegalArgumentException("--zones의 노드 수는 정수다");
			}
			if (n < 0 || zones.put(kv[0].strip(), n) != null) {
				throw new IllegalArgumentException("--zones에 음수나 같은 존이 두 번 있다");
			}
		}
		return zones;
	}

	public int total() {
		return sum(after);
	}

	/** 존마다의 권고를 {@code a=3,b=2} 꼴로. 확장 명령에 환경 변수로 넘긴다. */
	public String afterText() {
		StringBuilder s = new StringBuilder();
		after.forEach((zone, n) -> s.append(s.isEmpty() ? "" : ",").append(zone).append('=').append(n));
		return s.toString();
	}

	private static int sum(Map<String, Integer> zones) {
		return zones.values().stream().mapToInt(Integer::intValue).sum();
	}
}
