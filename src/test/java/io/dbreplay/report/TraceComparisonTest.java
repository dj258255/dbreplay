package io.dbreplay.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.dbreplay.trace.TraceHeader;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;
import io.dbreplay.trace.TraceWriter;

class TraceComparisonTest {

	/** 모양 하나를 값만 바꿔 times번, 매번 micros가 걸린 것으로 적는다. */
	private static void add(TraceWriter w, String sqlTemplate, int times, long micros, int error) throws IOException {
		for (int i = 0; i < times; i++) {
			w.write(new TraceRecord("A", i, micros, sqlTemplate.formatted(i), null, error, null, null, null));
		}
	}

	private interface Fill {
		void into(TraceWriter w) throws IOException;
	}

	private static TraceReader trace(Fill fill) throws IOException {
		StringWriter out = new StringWriter();
		try (TraceWriter w = new TraceWriter(out, TraceHeader.of("mysql", "test"))) {
			fill.into(w);
			w.write(TraceRecord.close("A", 1_000_000));
		}
		return new TraceReader(new StringReader(out.toString()));
	}

	@Test
	void ranksShapesByHowMuchTheirMeanChanged() throws IOException {
		TraceComparison c = TraceComparison.of(
				trace(w -> {
					add(w, "SELECT c FROM slow WHERE id = %d", 30, 100, 0);
					add(w, "SELECT c FROM fast WHERE id = %d", 30, 400, 0);
					add(w, "SELECT c FROM same WHERE id = %d", 30, 50, 0);
					add(w, "SELECT c FROM rare WHERE id = %d", 2, 10, 0);
					add(w, "SELECT c FROM gone WHERE id = %d", 30, 10, 0);
				}),
				trace(w -> {
					add(w, "SELECT c FROM slow WHERE id = %d", 30, 300, 0);
					add(w, "SELECT c FROM fast WHERE id = %d", 30, 200, 1146);
					add(w, "SELECT c FROM same WHERE id = %d", 30, 50, 0);
					add(w, "SELECT c FROM rare WHERE id = %d", 2, 9_000, 0);
					add(w, "SELECT c FROM added WHERE id = %d", 5, 10, 0);
				}));

		List<TraceComparison.Row> worst = c.worst(3, 20);
		assertEquals(List.of("SELECT c FROM slow WHERE id = ?"), worst.stream().map(TraceComparison.Row::shape).toList(),
				"두 번뿐인 rare는 횟수가 모자라 순위에 들지 않는다");
		assertEquals(3.0, worst.getFirst().ratio(), 1e-9);

		List<TraceComparison.Row> best = c.best(3, 20);
		assertEquals(List.of("SELECT c FROM fast WHERE id = ?"), best.stream().map(TraceComparison.Row::shape).toList());
		assertEquals(0.5, best.getFirst().ratio(), 1e-9);
		assertEquals(30, best.getFirst().after().errors());

		assertTrue(c.onlyBefore().containsKey("SELECT c FROM gone WHERE id = ?"));
		assertTrue(c.onlyAfter().containsKey("SELECT c FROM added WHERE id = ?"));
		assertEquals(122, c.totalBefore().count(), "연결 종료 줄은 세지 않는다");
		assertEquals(97, c.totalAfter().count());
		assertEquals(3, c.rows(20).size());
	}
}
