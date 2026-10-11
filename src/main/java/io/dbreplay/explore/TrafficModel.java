package io.dbreplay.explore;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import io.dbreplay.report.SqlShape;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;

/**
 * 트래픽의 모양. 어떤 SQL 모양이 전체 문장에서 얼마를 차지하는지로 나타낸다.
 *
 * @param statements 문장 수
 * @param spanMicros 첫 문장 시작부터 마지막 문장 시작까지
 * @param shares     많이 나온 모양부터 최대 50개의 비중
 */
public record TrafficModel(long statements, long spanMicros, Map<String, Double> shares) {

	private static final int KEPT = 50;

	public static TrafficModel of(TraceReader trace) throws IOException {
		Map<String, Long> counts = new HashMap<>();
		long statements = 0;
		long first = 0;
		long last = 0;
		for (TraceRecord r; (r = trace.next()) != null;) {
			if (r.endsSession()) {
				continue;
			}
			if (statements++ == 0) {
				first = r.startMicros();
			}
			last = r.startMicros();
			counts.merge(SqlShape.of(r.sql()), 1L, Long::sum);
		}
		long total = statements;
		Map<String, Double> shares = new LinkedHashMap<>();
		counts.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.limit(KEPT)
				.forEach(e -> shares.put(e.getKey(), (double) e.getValue() / total));
		return new TrafficModel(statements, last - first, shares);
	}

	public double qps() {
		return spanMicros == 0 ? 0 : statements / (spanMicros / 1e6);
	}

	/** 두 트래픽의 모양이 얼마나 다른가. 0이면 같고 1이면 겹치는 모양이 없다. 비중 차이의 합의 절반이다. */
	public double distance(TrafficModel other) {
		double sum = 0;
		for (Map.Entry<String, Double> e : shares.entrySet()) {
			sum += Math.abs(e.getValue() - other.shares.getOrDefault(e.getKey(), 0.0));
		}
		for (Map.Entry<String, Double> e : other.shares.entrySet()) {
			if (!shares.containsKey(e.getKey())) {
				sum += e.getValue();
			}
		}
		return sum / 2;
	}
}
