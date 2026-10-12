package io.dbreplay.explore;

import java.util.Map;

import io.dbreplay.metrics.DbMetricSource;
import io.dbreplay.replay.ReplaySummary;

/**
 * 한 라운드에서 얻은 것.
 *
 * @param speed         그 라운드의 배속
 * @param startedMillis 재생을 시작한 시각 (에포크 밀리초)
 * @param endedMillis   재생이 끝난 시각
 * @param summary       재생 요약
 * @param replica       종 DB의 트래픽을 함께 틀었으면 그 재생의 요약. 아니면 null
 * @param metrics       재생하는 동안 뜬 지표. 이름별로 시간 순서의 값
 */
public record RoundData(double speed, long startedMillis, long endedMillis, ReplaySummary summary, ReplaySummary replica,
		Map<String, double[]> metrics) {

	public RoundData(double speed, long startedMillis, long endedMillis, ReplaySummary summary, Map<String, double[]> metrics) {
		this(speed, startedMillis, endedMillis, summary, null, metrics);
	}

	/** 재생기가 늦어 계획을 못 지킨 문장의 비율. 주와 종을 함께 틀었으면 둘 중 큰 쪽이다. */
	public double distortion() {
		return Math.max(summary.replayerLateRatio(), replica == null ? 0 : replica.replayerLateRatio());
	}

	private static final double[] NONE = {};

	public double[] metric(String name) {
		return metrics.getOrDefault(name, NONE);
	}

	/** 그 지표의 가장 큰 값. 뜬 값이 없으면 0. */
	public double max(String name) {
		double max = 0;
		for (double v : metric(name)) {
			max = Math.max(max, v);
		}
		return max;
	}

	public double mean(String name) {
		double[] values = metric(name);
		double sum = 0;
		for (double v : values) {
			sum += v;
		}
		return values.length == 0 ? 0 : sum / values.length;
	}

	public int maxActiveSessions() {
		return (int) max(DbMetricSource.ACTIVE_SESSIONS);
	}
}
