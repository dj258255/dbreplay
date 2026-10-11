package io.dbreplay.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 지표를 일정 간격으로 떠서 시간 순서대로 모은다. 지표를 읽는 곳마다 스레드 하나가 제 간격으로 돈다.
 * 느린 곳(명령 실행)이 빠른 곳(DB 조회)의 간격을 늘리지 않게 하려는 것이다.
 */
public final class MetricSampler implements AutoCloseable {

	private final Map<String, List<Double>> series = new TreeMap<>();
	private final List<MetricSource> sources = new ArrayList<>();
	private final List<Thread> threads = new ArrayList<>();
	private volatile boolean running = true;

	/** 이 곳을 intervalMillis마다 읽기 시작한다. 닫을 때 이 곳도 함께 닫는다. */
	public MetricSampler add(MetricSource source, long intervalMillis) {
		sources.add(source);
		threads.add(Thread.ofPlatform().name("dbreplay-sampler").daemon().start(() -> loop(source, intervalMillis)));
		return this;
	}

	private void loop(MetricSource source, long intervalMillis) {
		try {
			while (running) {
				long started = System.nanoTime();
				Map<String, Double> values = source.read();
				synchronized (series) {
					values.forEach((name, value) -> series.computeIfAbsent(name, k -> new ArrayList<>()).add(value));
				}
				long rest = intervalMillis - (System.nanoTime() - started) / 1_000_000;
				if (rest > 0) {
					Thread.sleep(rest);
				}
			}
		}
		catch (Exception e) {
			// 읽지 못하면 그 곳은 여기서 멈춘다. 그때까지 뜬 값만 남는다
		}
	}

	/** 뜨기를 멈추고 지금까지 뜬 값을 지표별로, 시간 순서로 돌려준다. */
	public Map<String, double[]> stop() {
		running = false;
		for (Thread thread : threads) {
			thread.interrupt();
		}
		for (Thread thread : threads) {
			try {
				thread.join(2_000);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		Map<String, double[]> result = new TreeMap<>();
		synchronized (series) {
			series.forEach((name, values) -> result.put(name, values.stream().mapToDouble(Double::doubleValue).toArray()));
		}
		return result;
	}

	@Override
	public void close() {
		stop();
		sources.forEach(MetricSource::close);
	}
}
