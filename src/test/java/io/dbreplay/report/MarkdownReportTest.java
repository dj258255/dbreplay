package io.dbreplay.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.dbreplay.explore.Evaluation;
import io.dbreplay.explore.TrafficModel;
import io.dbreplay.trace.TraceHeader;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;
import io.dbreplay.trace.TraceWriter;

class MarkdownReportTest {

	private static TraceReader trace(long selectMicros, long rowsExamined, int insertError) throws IOException {
		StringWriter out = new StringWriter();
		try (TraceWriter w = new TraceWriter(out, TraceHeader.of("mysql", "test"))) {
			for (int i = 0; i < 100; i++) {
				w.write(new TraceRecord("A", i, selectMicros, "SELECT c FROM t WHERE id BETWEEN " + i + " AND 99", null, 0, null, null, 100L, rowsExamined, null));
				w.write(new TraceRecord("A", i, 50, "INSERT INTO t | u VALUES (" + i + ")", null, insertError, null, null, 0L, 0L, null));
			}
		}
		return new TraceReader(new StringReader(out.toString()));
	}

	private static RunRecord run(String version, double cpu) {
		return new RunRecord("2026-10-12T00:00:00Z", 1, "transaction", 200, 0, 0, 0.001, 5_000,
				Map.of("version", version, "max_connections", "1000"), Map.of("cpu_busy", new RunRecord.Stat(30, 10, cpu, cpu + 10)));
	}

	private static Evaluation evaluation(double ceiling) {
		return new Evaluation("2026-10-12T00:00:00Z", 30, Map.of(), Map.of(), "transaction", new TrafficModel(200, 1_000_000, Map.of()),
				ceiling, "OVERLOADED", ceiling * 4_000, 4_000, 0.3, 0.5);
	}

	private static String report(RunRecord before, RunRecord after, Evaluation evalBefore, Evaluation evalAfter) throws IOException {
		StringWriter out = new StringWriter();
		MarkdownReport.write(out, new MarkdownReport.Inputs("테이블 압축", TraceComparison.of(trace(100, 100, 0), trace(400, 100_000, 1062)),
				before, after, evalBefore, evalAfter, 3, 20));
		return out.toString();
	}

	@Test
	void putsEverythingTheArticlesReportHasIntoOneDocument() throws IOException {
		String md = report(run("8.4.11", 17.3), run("8.4.12", 19.3), evaluation(9.0), evaluation(3.5));

		assertTrue(md.startsWith("# 테이블 압축"));
		assertTrue(md.contains("**한 번씩 돌린 값이다.**"), "몇 번 돌린 결과인지가 머리에 있어야 한다");
		assertTrue(md.contains("| `SELECT c FROM t WHERE id BETWEEN ? AND ?` | 100 | 0.100ms → 0.400ms | +300.0% |"));
		assertTrue(md.contains("| `SELECT c FROM t WHERE id BETWEEN ? AND ?` | 100 | 100 → 100,000 | 1,000.0배 |"));
		assertTrue(md.contains("| [1062] | [] | 0 → 100 |"));
		assertTrue(md.contains("| cpu_busy | 17.3 / 27.3 | 19.3 / 29.3 |"));
		assertTrue(md.contains("| version | 8.4.11 | 8.4.12 |"));
		assertFalse(md.contains("| max_connections |"), "달라지지 않은 파라미터는 적지 않는다");
		assertTrue(md.contains("| 알람 없이 버틴 배속 | 9.00 | 3.50 |"));
		assertTrue(md.contains("| 수위 | 11% | 29% |"));
	}

	@Test
	void leavesOutTheSectionsItHasNoDataFor() throws IOException {
		String md = report(null, null, null, null);

		assertTrue(md.contains("## 나빠진 SQL 상위 3"));
		assertFalse(md.contains("## 재생하는 동안의 부하 지표"));
		assertFalse(md.contains("## 용량"));
	}

	/** SQL 안의 세로선이 표의 칸을 나누면 안 된다. */
	@Test
	void keepsATableCellInOnePiece() throws IOException {
		String md = report(null, null, null, null);

		assertTrue(md.contains("`INSERT INTO t \\| u VALUES (?)`"));
	}

	/** 되풀이한 평가가 한쪽에 있으면 상한의 범위를 표에 넣고, 마지막 문장도 되풀이를 밝히도록 바꾼다. */
	@Test
	void showsTheCeilingRangeWhenOneSideWasRepeated() throws IOException {
		Evaluation repeated = new Evaluation("2026-10-12T00:00:00Z", 30, Map.of(), Map.of(), "transaction",
				new TrafficModel(200, 1_000_000, Map.of()), 3.75, "OVERLOADED", 3.75 * 4_000, 4_000, 0.3, 0.5,
				List.of(8.5, 12.0, 8.5, 3.75, 8.0));
		String md = report(null, null, evaluation(9.0), repeated);

		assertTrue(md.contains("| 되풀이한 탐색의 상한 (최소 / 중앙값 / 최대) | 한 번 | 3.75 / 8.50 / 12.00 (5번) |"));
		assertTrue(md.contains("되풀이한 쪽의 상한은 가장 낮은 값이다. 한 번만 돌린 쪽은 그 한 번의 값이다."));
		int ceilingQpsRow = md.indexOf("| 상한 (초당 문장 수) |");
		int rangeRow = md.indexOf("| 되풀이한 탐색의 상한");
		int levelRow = md.indexOf("| 수위 |");
		assertTrue(ceilingQpsRow < rangeRow && rangeRow < levelRow,
				"범위 줄은 상한 줄 뒤, 수위 줄 앞에 있어야 한다");
	}

	/** 되풀이하지 않은 두 평가의 리포트는 상한 범위를 넣지 않고 지금의 문장을 그대로 둔다. */
	@Test
	void keepsTheSingleRunSentenceWhenNoSideWasRepeated() throws IOException {
		String md = report(null, null, evaluation(9.0), evaluation(3.5));

		assertFalse(md.contains("되풀이한"));
		assertTrue(md.contains("상한은 탐색을 한 번씩 돌린 값이다."));
	}

	private static Evaluation withRounds(double ceiling, List<List<Evaluation.RoundLog>> explorations) {
		return new Evaluation("2026-10-12T00:00:00Z", 30, Map.of(), Map.of(), "transaction",
				new TrafficModel(200, 1_000_000, Map.of()), ceiling, "OVERLOADED", ceiling * 4_000, 4_000, 0.3, 0.5,
				null, explorations);
	}

	/** 한쪽만 라운드를 남겼으면 그쪽 표가 나오고, 다른 쪽은 남기지 않았다고 적는다. */
	@Test
	void showsTheClimbForTheSideThatKeptRounds() throws IOException {
		Evaluation withClimb = withRounds(9.0, List.of(List.of(
				new Evaluation.RoundLog(1.0, "HELD", null, 0.0, Map.of("active_sessions", List.of(1.2, 5.0))),
				new Evaluation.RoundLog(4.0, "OVERLOADED", "active_sessions이(가) 3번 연속 32 이상 (최대 390)", 0.004,
						Map.of("active_sessions", List.of(40.1, 390.0))))));
		String md = report(null, null, withClimb, evaluation(3.5));

		assertTrue(md.contains("### 상승 과정: 변경 전"));
		assertTrue(md.contains("| 1 | 1.00 | 버팀 | 0.0% | 1.2 / 5.0 | |"), md);
		assertTrue(md.contains("| 1 | 4.00 | 과부하 | 0.4% | 40.1 / 390.0 | active_sessions이(가) 3번 연속 32 이상 (최대 390) |"), md);
		assertTrue(md.contains("### 상승 과정: 변경 후"));
		assertTrue(md.contains("라운드를 남기지 않은 평가 파일이다."));
	}

	/** 두 쪽 다 라운드를 남기지 않았으면 상승 과정을 넣지 않는다. */
	@Test
	void omitsTheClimbWhenNeitherSideKeptRounds() throws IOException {
		String md = report(null, null, evaluation(9.0), evaluation(3.5));

		assertFalse(md.contains("상승 과정"));
	}

	/** 되풀이한 평가면 탐색마다 라운드가 이어서 나오고 탐색 번호가 1, 2로 붙는다. */
	@Test
	void numbersEachExplorationInTheClimb() throws IOException {
		Evaluation withClimb = withRounds(9.0, List.of(
				List.of(new Evaluation.RoundLog(1.0, "HELD", null, 0.0, Map.of("active_sessions", List.of(1.0, 2.0)))),
				List.of(new Evaluation.RoundLog(2.0, "HELD", null, 0.0, Map.of("active_sessions", List.of(3.0, 4.0))))));
		String md = report(null, null, withClimb, evaluation(3.5));

		assertTrue(md.contains("| 1 | 1.00 |"), md);
		assertTrue(md.contains("| 2 | 2.00 |"), md);
	}

	/** 어떤 라운드에 없는 지표의 칸은 -이고, 이유에 세로선이 있어도 표의 열 수가 맞는다. */
	@Test
	void leavesMissingMetricsEmptyAndKeepsTheReasonInOneCell() throws IOException {
		Evaluation withClimb = withRounds(9.0, List.of(List.of(
				new Evaluation.RoundLog(1.0, "HELD", null, 0.0, Map.of("active_sessions", List.of(1.0, 2.0))),
				new Evaluation.RoundLog(2.0, "OVERLOADED", "cpu | busy", 0.1,
						Map.of("active_sessions", List.of(3.0, 4.0), "load1", List.of(7.0, 8.0))))));
		String md = report(null, null, withClimb, evaluation(3.5));

		assertTrue(md.contains("| 1 | 1.00 | 버팀 | 0.0% | 1.0 / 2.0 | - | |"), md);
		assertTrue(md.contains("cpu \\| busy"), md);
	}
}
