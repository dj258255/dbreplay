package io.dbreplay.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class HistogramMergeTest {

	/** 나눠 튼 재생기의 요약을 모으면 개수는 더하고 분포는 합치고 걸린 시간은 가장 긴 것이다 (#81). */
	@Test
	void mergesSummariesOfShards() {
		Histogram a = new Histogram();
		Histogram b = new Histogram();
		for (int i = 0; i < 90; i++) {
			a.record(10);
		}
		for (int i = 0; i < 10; i++) {
			b.record(5_000);
		}
		ReplaySummary one = new ReplaySummary(90, 3, 0, 0, 0, 0, 0, 2, 1_000, 2_000, a, new Histogram(), new Histogram());
		ReplaySummary two = new ReplaySummary(10, 1, 0, 1, 0, 10, 2, 2, 900, 2_500, b, new Histogram(), new Histogram());
		ReplaySummary merged = ReplaySummary.merge(List.of(one, two));

		assertEquals(100, merged.statements());
		assertEquals(4, merged.sessions());
		assertEquals(10, merged.late());
		assertEquals(2_500, merged.elapsedMicros());
		assertEquals(1_000, merged.traceSpanMicros());
		assertEquals(100, merged.lag().count());
		assertEquals(5_000, merged.lag().max());
		assertEquals(0.08, merged.replayerLateRatio(), 1e-9);
	}
}
