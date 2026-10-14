package io.dbreplay.metrics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class MetricSamplerTest {

	/** 값을 두 번 내고 세 번째에 실패하는 곳. 예외 메시지에 접속 정보가 섞여 있는 셈 치자. */
	private static final class FailingSource implements MetricSource {

		private final AtomicInteger calls = new AtomicInteger();

		@Override
		public Map<String, Double> read() {
			if (calls.incrementAndGet() >= 3) {
				throw new IllegalStateException("host=db password=secret");
			}
			return Map.of("ok", (double) calls.get());
		}
	}

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

	/** 값을 내다 도중에 실패한 곳은 실패로 적는다. 예외 메시지는 싣지 않는다(접속 정보가 섞일 수 있다). */
	@Test
	void recordsASourceThatStoppedMidway() throws InterruptedException {
		Map<String, double[]> series;
		List<String> failures;
		try (MetricSampler sampler = new MetricSampler()) {
			sampler.add(new FailingSource(), 10);
			sampler.add(() -> Map.of("other", 1.0), 10);
			Thread.sleep(100);
			series = sampler.stop();
			failures = sampler.failures();
		}

		assertEquals(1, failures.size(), failures.toString());
		assertTrue(failures.getFirst().contains("FailingSource"), failures.toString());
		assertTrue(failures.getFirst().contains("지표를 읽다 멈췄다"), failures.toString());
		assertTrue(failures.getFirst().contains("IllegalStateException"), failures.toString());
		assertFalse(failures.getFirst().contains("secret"), "예외 메시지는 싣지 않는다");
		assertTrue(series.get("other").length >= 3, "다른 곳은 계속 뜬다");
	}

	/** stop()으로 멈춘 곳은 실패가 아니다. 읽는 중에 오래 걸리는 곳도 마찬가지다. */
	@Test
	void doesNotRecordAStopAsAFailure() throws InterruptedException {
		List<String> failures;
		try (MetricSampler sampler = new MetricSampler()) {
			sampler.add(() -> Map.of("fast", 1.0), 10);
			sampler.add(() -> {
				Thread.sleep(5_000);
				return Map.of("slow", 1.0);
			}, 10);
			Thread.sleep(100);
			sampler.stop();
			failures = sampler.failures();
		}

		assertEquals(List.of(), failures);
	}

	/** 인터럽트를 무시하고 바쁘게 기다리다 실제 오류로 끝난 곳은 stop() 뒤에 끝나도 적힌다. */
	@Test
	void recordsAFailureThatLandsAfterStopWhenItIsNotFromTheInterrupt() throws InterruptedException {
		List<String> failures;
		try (MetricSampler sampler = new MetricSampler()) {
			sampler.add(() -> {
				long until = System.nanoTime() + 300_000_000L;
				while (System.nanoTime() < until) {
					// 인터럽트에 반응하지 않고 바쁘게 기다린다
				}
				throw new SQLException("연결이 끊겼다");
			}, 10);
			Thread.sleep(50);
			sampler.stop();
			failures = sampler.failures();
		}

		assertEquals(1, failures.size(), failures.toString());
		assertTrue(failures.getFirst().contains("SQLException"), failures.toString());
	}

	/** 인터럽트 때문에 끊긴 것(InterruptedException을 원인으로 가진 것)은 실패가 아니다. */
	@Test
	void doesNotRecordAnExceptionCausedByTheInterrupt() throws InterruptedException {
		List<String> failures;
		try (MetricSampler sampler = new MetricSampler()) {
			sampler.add(() -> {
				try {
					Thread.sleep(5_000);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new SQLException("읽기가 중단됐다", e);
				}
				return Map.of("slow", 1.0);
			}, 10);
			Thread.sleep(50);
			sampler.stop();
			failures = sampler.failures();
		}

		assertEquals(List.of(), failures);
	}

	/** 곳이 스스로 낸 실패도 합쳐진다. */
	@Test
	void mergesTheFailuresEachSourceReports() throws InterruptedException {
		List<String> failures;
		try (MetricSampler sampler = new MetricSampler()) {
			sampler.add(new MetricSource() {
				@Override
				public Map<String, Double> read() {
					return Map.of("ok", 1.0);
				}

				@Override
				public List<String> failures() {
					return List.of("스스로 낸 실패");
				}
			}, 10);
			Thread.sleep(50);
			sampler.stop();
			failures = sampler.failures();
		}

		assertEquals(List.of("스스로 낸 실패"), failures);
	}
}
