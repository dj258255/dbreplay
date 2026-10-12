package io.dbreplay.report;

import java.util.Arrays;
import java.util.DoubleSummaryStatistics;
import java.util.Map;
import java.util.TreeMap;

import io.dbreplay.replay.ReplaySummary;

/**
 * 재생 한 번을 리포트에 싣기 위해 남기는 기록. 그때의 대상 설정과 부하 지표, 재생이 계획을 얼마나 지켰는지를 담는다.
 *
 * @param recordedAt        재생이 끝난 시각 (ISO-8601)
 * @param speed             배속
 * @param unit              재생 단위
 * @param statements        실행한 문장 수
 * @param errors            대상에서 실패한 문장 수
 * @param origErrors        원본에서 실패했던 문장 수
 * @param replayerLateRatio 재생기가 늦어 계획을 못 지킨 문장의 비율
 * @param lagP99Micros      계획보다 늦게 나간 시간의 p99
 * @param profile           대상의 버전과 핵심 파라미터
 * @param metrics           재생하는 동안 뜬 부하 지표의 요약
 */
public record RunRecord(String recordedAt, double speed, String unit, long statements, long errors, long origErrors,
		double replayerLateRatio, long lagP99Micros, Map<String, String> profile, Map<String, Stat> metrics) {

	public record Stat(long samples, double min, double mean, double max) {
	}

	public static RunRecord of(String recordedAt, String unit, ReplaySummary summary, Map<String, String> profile,
			Map<String, double[]> series) {
		Map<String, Stat> metrics = new TreeMap<>();
		series.forEach((name, values) -> {
			DoubleSummaryStatistics s = Arrays.stream(values).summaryStatistics();
			if (s.getCount() > 0) {
				metrics.put(name, new Stat(s.getCount(), s.getMin(), s.getAverage(), s.getMax()));
			}
		});
		return new RunRecord(recordedAt, summary.speed(), unit, summary.statements(), summary.errors(), summary.origErrors(),
				summary.replayerLateRatio(), summary.lag().quantile(0.99), profile, metrics);
	}
}
