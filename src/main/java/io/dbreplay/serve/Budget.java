package io.dbreplay.serve;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.dbreplay.explore.Evaluation;
import io.dbreplay.explore.ScaleAdvice;
import io.dbreplay.explore.WaterLevel;

/**
 * 예산 보정(원문 대조 6, #88). 글의 화면은 용량 권고를 예산과 맞춰 본다. 권고 노드 수에 노드 하나의 비용을 곱해 비용의 변화를 낸다.
 * 권고는 explore와 같은 가정(읽기 상한이 노드 수에 비례한다)이고 확인된 것이 아니다. 비용은 사용자가 대상 옵션에 적는다.
 */
final class Budget {

	private Budget() {
	}

	/** 대상 옵션에 {@code --read-nodes}와 {@code --node-cost}가 없으면 비어 있다. */
	static Optional<Map<String, Object>> of(Evaluation e, Map<String, String> options) {
		if (!options.containsKey("--read-nodes") || !options.containsKey("--node-cost")) {
			return Optional.empty();
		}
		int nodes = Integer.parseInt(options.get("--read-nodes"));
		double cost = Double.parseDouble(options.get("--node-cost"));
		double recordedQps = e.traffic().qps();
		// 평가할 때 쓴 지금의 QPS가 녹화의 몇 배인지를 읽기와 쓰기에 똑같이 곱한다. explore가 나눠 내는 방식과 같다
		double scale = recordedQps == 0 ? 0 : e.currentQps() / recordedQps;
		WaterLevel read = e.replica() != null ? e.replica().level(e.low(), e.high())
				: new WaterLevel(e.traffic().readQps() * scale, e.traffic().readQps() * e.ceilingSpeed(), e.low(), e.high());
		WaterLevel write = new WaterLevel(e.traffic().writeQps() * scale, e.traffic().writeQps() * e.ceilingSpeed(), e.low(), e.high());
		int min = Integer.parseInt(options.getOrDefault("--min-read-nodes", "1"));
		int max = Integer.parseInt(options.getOrDefault("--max-read-nodes", Integer.toString(Integer.MAX_VALUE)));
		ScaleAdvice advice = ScaleAdvice.of(read, write, nodes, min, max);
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("readNodes", nodes);
		b.put("recommendedReadNodes", advice.recommendedReadNodes());
		b.put("nodeCost", cost);
		b.put("costChange", advice.readNodeChange() * cost);
		b.put("writeCapacityFactor", advice.writeCapacityFactor());
		return Optional.of(b);
	}
}
