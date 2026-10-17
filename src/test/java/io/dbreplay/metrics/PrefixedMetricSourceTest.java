package io.dbreplay.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PrefixedMetricSourceTest {

	/** 종 DB에서 뜬 지표는 이름 앞에 replica.이 붙는다. 실패도 어느 쪽의 것인지 보이게 한다 (#80). */
	@Test
	void prefixesNamesAndFailures() throws Exception {
		MetricSource inner = new MetricSource() {
			@Override
			public Map<String, Double> read() {
				return Map.of("active_sessions", 3.0);
			}

			@Override
			public List<String> failures() {
				return List.of("DbMetricSource이(가) 지표를 읽다 멈췄다 (SQLException)");
			}
		};
		PrefixedMetricSource replica = new PrefixedMetricSource("replica.", inner);
		assertEquals(Map.of("replica.active_sessions", 3.0), replica.read());
		assertEquals(List.of("replica. DbMetricSource이(가) 지표를 읽다 멈췄다 (SQLException)"), replica.failures());
	}
}
