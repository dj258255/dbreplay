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
	private final List<String> failures = new ArrayList<>();
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
			// 읽지 못하면 그 곳은 여기서 멈춘다. 그때까지 뜬 값만 남는다.
			// 멈추라는 요청 때문에 끊긴 것인지는 running이 아니라 예외가 인터럽트 때문인지로 가른다. stop()이 running을
			// 거짓으로 바꾼 직후에 진행 중이던 읽기가 실제 DB 오류로 끝날 수 있는데, 그것도 실패로 적어야 한다.
			// 예외의 메시지는 싣지 않는다(접속 정보가 섞일 수 있다).
			// 남는 한계: stop()은 각 곳을 2초까지 기다린다. 인터럽트에 반응하지 않는 읽기가 그보다 늦게 실패하면
			// failures()를 부른 뒤라 보지 못한다.
			if (!causedByInterrupt(e)) {
				synchronized (failures) {
					failures.add(source.getClass().getSimpleName() + "이(가) 지표를 읽다 멈췄다 ("
							+ e.getClass().getSimpleName() + ")");
				}
			}
		}
	}

	/**
	 * 예외 자신이나 그 원인 가운데 인터럽트로 끊긴 것이 있으면 참이다. {@link #stop()}이 건 인터럽트로 sleep이나
	 * 읽기가 끊긴 것은 실패가 아니다.
	 */
	private static boolean causedByInterrupt(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof InterruptedException || t instanceof java.nio.channels.ClosedByInterruptException) {
				return true;
			}
		}
		return false;
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

	/**
	 * 도중에 지표를 읽다 멈춘 곳들의 말을 모아 돌려준다. 표본기가 적은 것과 각 곳이 스스로 낸 것을 합친다.
	 * {@link #stop()} 뒤에 부른다.
	 */
	public List<String> failures() {
		List<String> all = new ArrayList<>();
		synchronized (failures) {
			all.addAll(failures);
		}
		for (MetricSource source : sources) {
			all.addAll(source.failures());
		}
		return all;
	}
}
