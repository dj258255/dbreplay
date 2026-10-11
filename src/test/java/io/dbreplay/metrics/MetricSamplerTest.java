package io.dbreplay.metrics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class MetricSamplerTest {

	@Test
	void collectsEachSourceAtItsOwnPace() throws InterruptedException {
		AtomicInteger fast = new AtomicInteger();
		AtomicInteger slow = new AtomicInteger();
		AtomicBoolean closed = new AtomicBoolean();
		Map<String, double[]> series;
		try (MetricSampler sampler = new MetricSampler()) {
			sampler.add(() -> Map.of("fast", (double) fast.incrementAndGet()), 10);
			sampler.add(new MetricSource() {
				@Override
				public Map<String, Double> read() {
					return Map.of("slow", (double) slow.incrementAndGet());
				}

				@Override
				public void close() {
					closed.set(true);
				}
			}, 200);
			Thread.sleep(450);
			series = sampler.stop();
		}

		assertTrue(series.get("fast").length >= 15, "10ms 간격: " + series.get("fast").length);
		assertTrue(series.get("slow").length <= 4, "200ms 간격은 빠른 쪽에 끌려가지 않는다: " + series.get("slow").length);
		assertEquals(1.0, series.get("fast")[0]);
		assertTrue(closed.get());
	}

	/** 한 곳이 읽다 실패해도 다른 곳은 계속 뜬다. */
	@Test
	void keepsSamplingTheOthersWhenOneSourceFails() throws InterruptedException {
		Map<String, double[]> series;
		try (MetricSampler sampler = new MetricSampler()) {
			sampler.add(() -> {
				throw new IllegalStateException("대상이 응답하지 않는다");
			}, 10);
			sampler.add(() -> Map.of("ok", 1.0), 10);
			Thread.sleep(100);
			series = sampler.stop();
		}

		assertEquals(1, series.size());
		assertTrue(series.get("ok").length >= 3);
	}

	@Test
	void readsNameValueLinesFromACommand() throws Exception {
		Map<String, Double> values = new CommandMetricSource(
				"echo 'load1 3.25'; echo 'cpu_busy=71.5'; echo 'not a metric'; echo; echo 'broken x'").read();

		assertEquals(Map.of("load1", 3.25, "cpu_busy", 71.5), values);
		assertArrayEquals(new String[] {"load1", "cpu_busy"}, values.keySet().toArray());
	}
}
