package io.dbreplay.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class DbMetricSourceTest {

	/** 두 표본 사이의 초당 횟수. 표본기 자신이 보탠 횟수는 뺀다. */
	@Test
	void dividesTheIncreaseByTheElapsedTime() {
		assertEquals(999.0, DbMetricSource.rate(1_000, 0, 2_000, 1_000_000_000L, 1), 1e-9);
		assertEquals(1_998.0, DbMetricSource.rate(1_000, 0, 2_000, 500_000_000L, 1), 1e-9, "같은 증가가 절반 시간이면 두 배");
	}

	/** 시간이 0이거나 거꾸로면 0, 누적 값이 줄었으면 0, 자신이 보탠 것이 차이보다 크면 0이다(음수를 내지 않는다). */
	@Test
	void returnsZeroWhenTheTimeOrTheCountsMakeNoSense() {
		assertEquals(0.0, DbMetricSource.rate(1_000, 1_000_000_000L, 2_000, 1_000_000_000L, 0), 1e-9, "시간이 0");
		assertEquals(0.0, DbMetricSource.rate(1_000, 1_000_000_000L, 2_000, 500_000_000L, 0), 1e-9, "시간이 거꾸로");
		assertEquals(0.0, DbMetricSource.rate(2_000, 0, 1_000, 1_000_000_000L, 0), 1e-9, "서버가 다시 떠서 누적 값이 줄었다");
		assertEquals(0.0, DbMetricSource.rate(1_000, 0, 1_002, 1_000_000_000L, 5), 1e-9, "자신이 보탠 것이 차이보다 크다");
	}

	/** 초당 횟수 조회가 실패하면 그것만 꺼지고 기존 값은 남는다. 다음 표본에서 다시 부르지 않는다. */
	@Test
	void turnsOffTheRateCounterWhenItFailsAndKeepsTheRest() {
		AtomicInteger rateCalls = new AtomicInteger();
		AtomicInteger slowCalls = new AtomicInteger();
		Map<String, Double> values = new HashMap<>(Map.of("active_sessions", 7.0));
		DbMetricSource.Counters counters = new DbMetricSource.Counters("qps",
				() -> {
					rateCalls.incrementAndGet();
					throw new SQLException("Questions를 읽을 수 없다");
				}, 3,
				() -> {
					slowCalls.incrementAndGet();
					return 1_000;
				});

		counters.addTo(values, 0);
		counters.addTo(values, 1_000_000_000L);

		assertEquals(7.0, values.get("active_sessions"), "기존 값이 그대로 있다");
		assertFalse(values.containsKey("qps"));
		assertEquals(1, rateCalls.get(), "실패한 조회는 다시 부르지 않는다");
		assertEquals(2, slowCalls.get(), "느리게 끝난 문장용은 계속 뜬다");
		assertEquals(0.0, values.get("slow_finished_total"));
	}

	/** 느리게 끝난 문장 조회가 실패하면 그것만 꺼지고 초당 횟수는 계속 뜬다. */
	@Test
	void turnsOffTheSlowFinishedCounterWhenItFailsAndKeepsTheRate() {
		AtomicInteger slowCalls = new AtomicInteger();
		AtomicLong rateValue = new AtomicLong();
		Map<String, Double> values = new HashMap<>();
		DbMetricSource.Counters counters = new DbMetricSource.Counters("qps", () -> rateValue.addAndGet(2_000), 0,
				() -> {
					slowCalls.incrementAndGet();
					throw new SQLException("분포를 읽을 수 없다");
				});

		counters.addTo(values, 0);
		counters.addTo(values, 1_000_000_000L);

		assertEquals(2_000.0, values.get("qps"), "초당 횟수는 계속 뜬다");
		assertFalse(values.containsKey("slow_finished_total"));
		assertEquals(1, slowCalls.get(), "실패한 조회는 다시 부르지 않는다");
	}

	/** 느리게 끝난 문장용의 기준 잡기: 첫 표본은 0, 그 뒤는 증가분, 분포를 비우면 기준을 다시 잡는다. */
	@Test
	void baselinesTheSlowFinishedCounter() {
		AtomicLong raw = new AtomicLong(1_000);
		Map<String, Double> values = new HashMap<>();
		DbMetricSource.Counters counters = new DbMetricSource.Counters("qps", null, 0, () -> raw.get());

		counters.addTo(values, 0);
		assertEquals(0.0, values.get("slow_finished_total"));

		raw.set(1_030);
		counters.addTo(values, 1);
		assertEquals(30.0, values.get("slow_finished_total"));

		raw.set(20);
		counters.addTo(values, 2);
		assertEquals(0.0, values.get("slow_finished_total"), "서버가 분포를 비웠다. 기준을 다시 잡는다");

		raw.set(25);
		counters.addTo(values, 3);
		assertEquals(5.0, values.get("slow_finished_total"));
	}

	/** 초당 횟수는 첫 표본에 내지 않고 둘째부터 낸다. */
	@Test
	void rateAppearsFromTheSecondSample() {
		AtomicLong raw = new AtomicLong(1_000);
		Map<String, Double> values = new HashMap<>();
		DbMetricSource.Counters counters = new DbMetricSource.Counters("qps", () -> raw.get(), 0, null);

		counters.addTo(values, 0);
		assertFalse(values.containsKey("qps"), "첫 표본에는 직전 값이 없다");

		raw.set(2_000);
		counters.addTo(values, 1_000_000_000L);
		assertEquals(1_000.0, values.get("qps"));
	}

	/** 그 기종이 내지 못하는 지표(null)는 건너뛴다. */
	@Test
	void skipsCountersTheEngineDoesNotHave() {
		Map<String, Double> values = new HashMap<>(Map.of("active_sessions", 1.0));

		new DbMetricSource.Counters("tps", null, 0, null).addTo(values, 1_000_000_000L);

		assertEquals(Map.of("active_sessions", 1.0), values);
		assertTrue(values.containsKey("active_sessions"));
	}
}