package io.dbreplay.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
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
				},
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
		DbMetricSource.Counters counters = new DbMetricSource.Counters("qps", () -> rateValue.addAndGet(2_000),
				() -> {
					slowCalls.incrementAndGet();
					throw new SQLException("분포를 읽을 수 없다");
				});

		counters.addTo(values, 0);
		counters.addTo(values, 1_000_000_000L);

		// 느리게 끝난 문장 조회가 꺼졌으니 자신의 조회는 2개만 뺀다: (4000 - 2000 - 2) / 1 = 1998
		assertEquals(1_998.0, values.get("qps"), 1e-9, "초당 횟수는 계속 뜬다");
		assertFalse(values.containsKey("slow_finished_total"));
		assertEquals(1, slowCalls.get(), "실패한 조회는 다시 부르지 않는다");
	}

	/** 느리게 끝난 문장의 누적: 첫 표본은 0, 그 뒤로 서버의 값이 늘면 늘어난 만큼, 줄면 지금 값만큼 더한다. */
	@Test
	void accumulatesTheSlowFinishedCounterAcrossResets() {
		AtomicLong raw = new AtomicLong(1_000);
		Map<String, Double> values = new HashMap<>();
		DbMetricSource.Counters counters = new DbMetricSource.Counters("qps", null, () -> raw.get());

		counters.addTo(values, 0);
		assertEquals(0.0, values.get("slow_finished_total"));

		raw.set(1_030);
		counters.addTo(values, 1);
		assertEquals(30.0, values.get("slow_finished_total"));

		raw.set(20);
		counters.addTo(values, 2);
		assertEquals(50.0, values.get("slow_finished_total"), "분포를 비운 뒤 다시 쌓인 20을 더한다");

		raw.set(25);
		counters.addTo(values, 3);
		assertEquals(55.0, values.get("slow_finished_total"));
	}

	/**
	 * PostgreSQL에 pg_stat_statements가 있으면 tps와 qps를 함께 낸다. 자신이 보낸 조회는 셋(기존, tps, qps)이고
	 * 트랜잭션도 셋이다. 둘 다에서 셋을 뺀다 (#82).
	 */
	@Test
	void producesTpsAndQpsTogetherAndSubtractsAllOwnQueries() {
		AtomicLong xacts = new AtomicLong(100);
		AtomicLong calls = new AtomicLong(1_000);
		Map<String, Double> values = new HashMap<>();
		DbMetricSource.Counters counters = new DbMetricSource.Counters(List.of(
				new DbMetricSource.Rate("tps", () -> xacts.get()),
				new DbMetricSource.Rate("qps", () -> calls.get())), null);

		counters.addTo(values, 0);
		xacts.set(153);
		calls.set(1_503);
		counters.addTo(values, 1_000_000_000L);

		assertEquals(50.0, values.get("tps"), 1e-9);
		assertEquals(500.0, values.get("qps"), 1e-9);
	}

	/** 초당 횟수는 첫 표본에 내지 않고 둘째부터 낸다. */
	@Test
	void rateAppearsFromTheSecondSample() {
		AtomicLong raw = new AtomicLong(1_000);
		Map<String, Double> values = new HashMap<>();
		DbMetricSource.Counters counters = new DbMetricSource.Counters("qps", () -> raw.get(), null);

		counters.addTo(values, 0);
		assertFalse(values.containsKey("qps"), "첫 표본에는 직전 값이 없다");

		raw.set(2_000);
		counters.addTo(values, 1_000_000_000L);
		// 자신의 조회 둘(기존 + 초당 횟수)을 뺀다: (2000 - 1000 - 2) / 1 = 998
		assertEquals(998.0, values.get("qps"), 1e-9);
	}

	/** 그 기종이 내지 못하는 지표(null)는 건너뛴다. */
	@Test
	void skipsCountersTheEngineDoesNotHave() {
		Map<String, Double> values = new HashMap<>(Map.of("active_sessions", 1.0));

		new DbMetricSource.Counters("tps", null, null).addTo(values, 1_000_000_000L);

		assertEquals(Map.of("active_sessions", 1.0), values);
	}

	/** 값을 낸 뒤 실패하면 실패로 적는다. 처음부터 실패한 것은 그 지표가 원래 없는 것이라 적지 않는다. */
	@Test
	void recordsFailuresOnlyAfterAValueWasProduced() {
		AtomicInteger rateCalls = new AtomicInteger();
		DbMetricSource.Counters rateAfter = new DbMetricSource.Counters("qps", () -> {
			if (rateCalls.incrementAndGet() >= 3) {
				throw new SQLException("끊겼다", "08006");
			}
			return 1_000 + rateCalls.get() * 1_000;
		}, null);
		rateAfter.addTo(new HashMap<>(), 0);
		rateAfter.addTo(new HashMap<>(), 1_000_000_000L);
		rateAfter.addTo(new HashMap<>(), 2_000_000_000L);

		assertEquals(1, rateAfter.failures().size(), rateAfter.failures().toString());
		assertTrue(rateAfter.failures().getFirst().contains("qps"), rateAfter.failures().toString());
		assertTrue(rateAfter.failures().getFirst().contains("08006"), rateAfter.failures().toString());

		DbMetricSource.Counters rateFromStart = new DbMetricSource.Counters("qps", () -> {
			throw new SQLException("권한 없다");
		}, null);
		rateFromStart.addTo(new HashMap<>(), 0);
		rateFromStart.addTo(new HashMap<>(), 1_000_000_000L);

		assertEquals(List.of(), rateFromStart.failures(), "처음부터 실패한 것은 적지 않는다");

		AtomicInteger slowCalls = new AtomicInteger();
		DbMetricSource.Counters slowAfter = new DbMetricSource.Counters("qps", null, () -> {
			if (slowCalls.incrementAndGet() >= 2) {
				throw new SQLException("끊겼다", "08006");
			}
			return 1_000;
		});
		slowAfter.addTo(new HashMap<>(), 0);
		slowAfter.addTo(new HashMap<>(), 1);

		assertEquals(1, slowAfter.failures().size(), slowAfter.failures().toString());
		assertTrue(slowAfter.failures().getFirst().contains("slow_finished_total"), slowAfter.failures().toString());

		DbMetricSource.Counters slowFromStart = new DbMetricSource.Counters("qps", null, () -> {
			throw new SQLException("권한 없다");
		});
		slowFromStart.addTo(new HashMap<>(), 0);

		assertEquals(List.of(), slowFromStart.failures(), "처음부터 실패한 것은 적지 않는다");
	}

	/** 자신의 조회 수를 센다: 기존 조회 하나 + 초당 횟수 하나 + (느리게 끝난 문장이 켜져 있으면) 하나. */
	@Test
	void subtractsTheQueryCountThisConnectionSends() {
		AtomicLong withSlowRate = new AtomicLong();
		Map<String, Double> withSlow = new HashMap<>();
		DbMetricSource.Counters three = new DbMetricSource.Counters("qps", () -> withSlowRate.addAndGet(2_000), () -> 1_000);
		three.addTo(withSlow, 0);
		three.addTo(withSlow, 1_000_000_000L);

		assertEquals(1_997.0, withSlow.get("qps"), 1e-9, "켜져 있으면 3을 뺀다");

		AtomicLong withoutSlowRate = new AtomicLong();
		Map<String, Double> withoutSlow = new HashMap<>();
		DbMetricSource.Counters two = new DbMetricSource.Counters("qps", () -> withoutSlowRate.addAndGet(2_000), null);
		two.addTo(withoutSlow, 0);
		two.addTo(withoutSlow, 1_000_000_000L);

		assertEquals(1_998.0, withoutSlow.get("qps"), 1e-9, "꺼져 있으면 2를 뺀다");
	}

	/** 도중에 느리게 끝난 문장 조회가 꺼지면 그 뒤로는 2만 뺀다. */
	@Test
	void subtractsTwoAfterTheSlowCounterTurnsOff() {
		AtomicLong rate = new AtomicLong();
		Map<String, Double> values = new HashMap<>();
		DbMetricSource.Counters counters = new DbMetricSource.Counters("qps", () -> rate.addAndGet(2_000), () -> {
			throw new SQLException("권한 없다");
		});

		counters.addTo(values, 0);
		counters.addTo(values, 1_000_000_000L);

		assertEquals(1_998.0, values.get("qps"), 1e-9, "느리게 끝난 문장 조회가 꺼져 2만 뺀다");
	}
}