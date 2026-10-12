package io.dbreplay.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

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

	/** 실행 시간은 같아도 읽은 행 수가 늘었으면 실행 계획이 바뀐 것이다. 실험 02에서 통계가 틀어져 범위 조회가 풀 스캔이 됐다. */
	@Test
	void findsShapesThatStartedReadingManyMoreRows() throws IOException {
		TraceComparison c = TraceComparison.of(
				trace(w -> {
					for (int i = 0; i < 30; i++) {
						w.write(new TraceRecord("A", i, 100, "SELECT c FROM t WHERE id BETWEEN " + i + " AND 99", null, 0, null, null, 100L, 100L, null));
						w.write(new TraceRecord("A", i, 100, "SELECT c FROM t WHERE id = " + i, null, 0, null, null, 1L, 1L, null));
					}
				}),
				trace(w -> {
					for (int i = 0; i < 30; i++) {
						w.write(new TraceRecord("A", i, 100, "SELECT c FROM t WHERE id BETWEEN " + i + " AND 99", null, 0, null, null, 100L, 100_000L, null));
						w.write(new TraceRecord("A", i, 100, "SELECT c FROM t WHERE id = " + i, null, 0, null, null, 1L, 1L, null));
					}
				}));

		List<TraceComparison.Row> more = c.moreRowsExamined(3, 20, 2);
		assertEquals(List.of("SELECT c FROM t WHERE id BETWEEN ? AND ?"), more.stream().map(TraceComparison.Row::shape).toList());
		assertEquals(1_000.0, more.getFirst().rowsRatio(), 1e-9);
		assertEquals(1.0, more.getFirst().ratio(), 1e-9, "실행 시간은 그대로다");
	}

	@Test
	void saysNothingAboutRowsWhenTheRecordingHasNone() throws IOException {
		TraceComparison c = TraceComparison.of(
				trace(w -> add(w, "SELECT c FROM t WHERE id = %d", 30, 100, 0)),
				trace(w -> add(w, "SELECT c FROM t WHERE id = %d", 30, 100, 0)));

		assertTrue(c.moreRowsExamined(3, 20, 2).isEmpty());
		assertEquals(false, c.totalBefore().knowsRows());
	}

	/** 뒤에서만 난 오류와 앞에서만 난 오류를 나눠 본다. 번호가 바뀐 것은 양쪽에 다 나온다. */
	@Test
	void tellsNewErrorsFromGoneAndChangedOnes() throws IOException {
		TraceComparison c = TraceComparison.of(
				trace(w -> {
					add(w, "INSERT INTO a VALUES (%d)", 3, 100, 0);
					add(w, "INSERT INTO b VALUES (%d)", 3, 100, 1062);
					add(w, "INSERT INTO c VALUES (%d)", 3, 100, 1213);
					add(w, "INSERT INTO d VALUES (%d)", 3, 100, 1062);
				}),
				trace(w -> {
					add(w, "INSERT INTO a VALUES (%d)", 3, 100, 1146);
					add(w, "INSERT INTO b VALUES (%d)", 3, 100, 0);
					add(w, "INSERT INTO c VALUES (%d)", 3, 100, 1205);
					add(w, "INSERT INTO d VALUES (%d)", 3, 100, 1062);
				}));

		Map<String, TraceComparison.Row> changed = new java.util.HashMap<>();
		c.errorChanges().forEach(r -> changed.put(r.shape(), r));
		assertEquals(java.util.Set.of("INSERT INTO a VALUES (?)", "INSERT INTO b VALUES (?)", "INSERT INTO c VALUES (?)"), changed.keySet());
		assertEquals(java.util.Set.of(1146), changed.get("INSERT INTO a VALUES (?)").newErrors());
		assertEquals(java.util.Set.of(1062), changed.get("INSERT INTO b VALUES (?)").goneErrors());
		assertEquals(java.util.Set.of(1205), changed.get("INSERT INTO c VALUES (?)").newErrors());
		assertEquals(java.util.Set.of(1213), changed.get("INSERT INTO c VALUES (?)").goneErrors());
		assertEquals(9, c.totalAfter().errors());
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
