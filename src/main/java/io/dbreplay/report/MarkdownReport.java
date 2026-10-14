package io.dbreplay.report;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import io.dbreplay.explore.CeilingRange;
import io.dbreplay.explore.Evaluation;

/**
 * 변경 전후의 비교를 한 문서로 낸다. 메이투안 글의 리포트가 싣는 것을 싣는다. 나빠진 SQL과 좋아진 SQL, 부하 지표,
 * 핵심 파라미터, 그리고 용량 탐색을 했으면 상한과 수위다.
 *
 * <p>문서의 머리에 몇 번 돌린 결과인지를 적는다. 이 도구의 수치는 실행마다 흔들려서, 한 번의 결과를 근거로 쓰면
 * 안 된다는 것을 읽는 사람이 먼저 봐야 한다.
 */
public final class MarkdownReport {

	/** @param runBefore 변경 전 재생의 기록. 없으면 null. 나머지 기록도 같다 */
	public record Inputs(String title, TraceComparison comparison, RunRecord runBefore, RunRecord runAfter,
			Evaluation evaluationBefore, Evaluation evaluationAfter, int top, long minCount) {
	}

	private MarkdownReport() {
	}

	public static void write(Writer writer, Inputs in) throws IOException {
		PrintWriter out = new PrintWriter(writer);
		TraceComparison c = in.comparison();
		TraceComparison.Side b = c.totalBefore();
		TraceComparison.Side a = c.totalAfter();

		out.printf("# %s%n%n", in.title());
		out.printf("변경 전과 변경 후의 샌드박스에 같은 트래픽을 한 번씩 틀어 비교한 결과다. **한 번씩 돌린 값이다.** "
				+ "같은 절차를 다시 돌리면 수치가 달라진다. 방향이 여러 번 같게 나온 것만 근거로 쓴다.%n%n");
		if (in.runBefore() != null && in.runAfter() != null) {
			out.printf("| | 변경 전 | 변경 후 |%n|---|---|---|%n");
			out.printf("| 재생한 시각 | %s | %s |%n", in.runBefore().recordedAt(), in.runAfter().recordedAt());
			out.printf("| 배속과 단위 | %.1f배속, %s | %.1f배속, %s |%n", in.runBefore().speed(), in.runBefore().unit(),
					in.runAfter().speed(), in.runAfter().unit());
			out.printf("| 재생한 문장 | %,d | %,d |%n", in.runBefore().statements(), in.runAfter().statements());
			out.printf("| 재생 오류 | %,d | %,d |%n", in.runBefore().errors(), in.runAfter().errors());
			out.printf("| 재생기가 늦은 문장 | %.2f%% | %.2f%% |%n%n", in.runBefore().replayerLateRatio() * 100,
					in.runAfter().replayerLateRatio() * 100);
		}

		out.printf("## 전체%n%n| | 변경 전 | 변경 후 | 변화 |%n|---|---|---|---|%n");
		out.printf("| 문장 수 | %,d | %,d | |%n", b.count(), a.count());
		out.printf("| 실패한 문장 | %,d | %,d | |%n", b.errors(), a.errors());
		out.printf("| 실행 시간 평균 | %s | %s | %s |%n", ms(b.meanMicros()), ms(a.meanMicros()), change(b.meanMicros(), a.meanMicros()));
		out.printf("| 실행 시간 중앙값 | %s | %s | %s |%n", ms(b.quantile(0.5)), ms(a.quantile(0.5)), change(b.quantile(0.5), a.quantile(0.5)));
		out.printf("| 실행 시간 p99 | %s | %s | %s |%n%n", ms(b.quantile(0.99)), ms(a.quantile(0.99)), change(b.quantile(0.99), a.quantile(0.99)));
		out.printf("실행 시간은 서버가 잰 값이다. 양쪽에서 %,d번 이상 실행된 SQL 모양만 아래 순위에 올린다.%n%n", in.minCount());

		rows(out, "나빠진 SQL 상위 " + in.top(), c.worst(in.top(), in.minCount()));
		rows(out, "좋아진 SQL 상위 " + in.top(), c.best(in.top(), in.minCount()));

		out.printf("## 읽은 행 수가 2배 이상 늘어난 SQL%n%n");
		if (!b.knowsRows() || !a.knowsRows()) {
			out.printf("이 녹화에는 읽은 행 수가 없다.%n%n");
		}
		else {
			List<TraceComparison.Row> more = c.moreRowsExamined(in.top(), in.minCount(), 2);
			if (more.isEmpty()) {
				out.printf("없다. 실행 계획이 크게 바뀐 흔적이 없다는 뜻이다.%n%n");
			}
			else {
				out.printf("| SQL 모양 | 횟수 | 문장당 읽은 행 (전 → 후) | 배율 |%n|---|---|---|---|%n");
				for (TraceComparison.Row r : more) {
					out.printf("| `%s` | %,d | %,.0f → %,.0f | %,.1f배 |%n", cell(r.shape()), r.after().count(),
							r.before().meanRowsExamined(), r.after().meanRowsExamined(), r.rowsRatio());
				}
				out.println();
			}
		}

		out.printf("## 오류가 달라진 SQL%n%n");
		List<TraceComparison.Row> errors = c.errorChanges();
		if (errors.isEmpty()) {
			out.printf("없다.%n%n");
		}
		else {
			out.printf("| SQL 모양 | 새로 난 오류 | 사라진 오류 | 실패 (전 → 후) |%n|---|---|---|---|%n");
			for (TraceComparison.Row r : errors) {
				out.printf("| `%s` | %s | %s | %,d → %,d |%n", cell(r.shape()), r.newErrors(), r.goneErrors(),
						r.before().errors(), r.after().errors());
			}
			out.println();
		}
		if (!c.onlyBefore().isEmpty() || !c.onlyAfter().isEmpty()) {
			out.printf("한쪽에만 있는 SQL 모양이 변경 전에 %d개, 변경 후에 %d개 있다. "
					+ "실패한 문장을 로그에 적지 않는 서버에서는 새로 실패한 문장이 '변경 전에만'으로 나온다.%n%n",
					c.onlyBefore().size(), c.onlyAfter().size());
			out.printf("| 어느 쪽 | SQL 모양 | 횟수 | 실패 |%n|---|---|---|---|%n");
			oneSided(out, "변경 전에만", c.onlyBefore(), in.top());
			oneSided(out, "변경 후에만", c.onlyAfter(), in.top());
			out.println();
		}

		if (in.runBefore() != null && in.runAfter() != null) {
			metrics(out, in.runBefore(), in.runAfter());
			profile(out, in.runBefore(), in.runAfter());
		}
		if (in.evaluationBefore() != null && in.evaluationAfter() != null) {
			capacity(out, in.evaluationBefore(), in.evaluationAfter());
		}
		out.flush();
	}

	private static void rows(PrintWriter out, String title, List<TraceComparison.Row> rows) {
		out.printf("## %s%n%n", title);
		if (rows.isEmpty()) {
			out.printf("없다.%n%n");
			return;
		}
		out.printf("| SQL 모양 | 횟수 | 평균 (전 → 후) | 변화 | p99 (전 → 후) |%n|---|---|---|---|---|%n");
		for (TraceComparison.Row r : rows) {
			out.printf("| `%s` | %,d | %s → %s | %+.1f%% | %s → %s |%n", cell(r.shape()), r.after().count(),
					ms(r.before().meanMicros()), ms(r.after().meanMicros()), (r.ratio() - 1) * 100,
					ms(r.before().quantile(0.99)), ms(r.after().quantile(0.99)));
		}
		out.println();
	}

	private static void metrics(PrintWriter out, RunRecord before, RunRecord after) {
		Set<String> names = new TreeSet<>(before.metrics().keySet());
		names.addAll(after.metrics().keySet());
		out.printf("## 재생하는 동안의 부하 지표%n%n");
		if (names.isEmpty()) {
			out.printf("뜨지 않았다.%n%n");
			return;
		}
		out.printf("칸의 값은 평균 / 최대다.%n%n| 지표 | 변경 전 | 변경 후 |%n|---|---|---|%n");
		for (String name : names) {
			out.printf("| %s | %s | %s |%n", name, stat(before.metrics().get(name)), stat(after.metrics().get(name)));
		}
		out.println();
	}

	private static void profile(PrintWriter out, RunRecord before, RunRecord after) {
		Set<String> names = new TreeSet<>(before.profile().keySet());
		names.addAll(after.profile().keySet());
		out.printf("## 버전과 핵심 파라미터%n%n");
		boolean any = false;
		for (String name : names) {
			String x = before.profile().get(name);
			String y = after.profile().get(name);
			if (!Objects.equals(x, y)) {
				if (!any) {
					out.printf("달라진 것만 적는다.%n%n| 항목 | 변경 전 | 변경 후 |%n|---|---|---|%n");
					any = true;
				}
				out.printf("| %s | %s | %s |%n", name, x, y);
			}
		}
		if (!any) {
			out.printf("버전 %s. 살펴본 파라미터 %d개가 양쪽에서 같다.%n", before.profile().get("version"), names.size() - 1);
		}
		out.println();
	}

	private static void capacity(PrintWriter out, Evaluation before, Evaluation after) {
		CeilingRange beforeRange = before.ceilingRange();
		CeilingRange afterRange = after.ceilingRange();
		out.printf("## 용량%n%n| | 변경 전 | 변경 후 |%n|---|---|---|%n");
		out.printf("| 알람 없이 버틴 배속 | %.2f | %.2f |%n", before.ceilingSpeed(), after.ceilingSpeed());
		out.printf("| 상한 (초당 문장 수) | %,.0f | %,.0f |%n", before.ceilingQps(), after.ceilingQps());
		// 되풀이한 쪽이 하나라도 있으면 수위 줄 앞에 상한의 범위를 밝힌다. 두 쪽 다 한 번 돌렸으면 지금과 같다
		if (beforeRange != null || afterRange != null) {
			out.printf("| 되풀이한 탐색의 상한 (최소 / 중앙값 / 최대) | %s | %s |%n", rangeCell(beforeRange),
					rangeCell(afterRange));
		}
		out.printf("| 수위 | %.0f%% | %.0f%% |%n", before.level().level() * 100, after.level().level() * 100);
		// 종 DB를 함께 튼 평가가 하나라도 있으면 그 수위 줄을 더한다. 둘 다 없으면 지금과 같다
		if (before.replica() != null || after.replica() != null) {
			out.printf("| 종 DB의 수위 | %s | %s |%n", replicaLevelCell(before), replicaLevelCell(after));
		}
		out.printf("| 평가한 시각 | %s | %s |%n%n", before.evaluatedAt(), after.evaluatedAt());
		if (beforeRange == null && afterRange == null) {
			out.printf("상한은 탐색을 한 번씩 돌린 값이다.%n%n");
		}
		else {
			out.printf("되풀이한 쪽의 상한은 가장 낮은 값이다. 한 번만 돌린 쪽은 그 한 번의 값이다.%n%n");
		}
		// 라운드를 남긴 평가가 하나라도 있으면 그 상한이 나온 과정을 따로 낸다. 둘 다 없으면 지금과 같다
		if (hasRounds(before) || hasRounds(after)) {
			ascending(out, "변경 전", before);
			ascending(out, "변경 후", after);
		}
	}

	private static boolean hasRounds(Evaluation evaluation) {
		return evaluation.explorations() != null && !evaluation.explorations().isEmpty();
	}

	/** 상한이 어떤 라운드들에서 나왔는지를 표로 낸다. 되풀이했으면 탐색마다 라운드가 이어서 나온다. */
	private static void ascending(PrintWriter out, String label, Evaluation evaluation) {
		out.printf("### 상승 과정: %s%n%n", label);
		if (!hasRounds(evaluation)) {
			out.printf("라운드를 남기지 않은 평가 파일이다.%n%n");
			return;
		}
		// 지표 열은 그 평가의 라운드들에 나온 이름 전부를 이름 순으로 둔다. 어떤 라운드에 없으면 그 칸은 -
		Set<String> names = new TreeSet<>();
		for (List<Evaluation.RoundLog> rounds : evaluation.explorations()) {
			for (Evaluation.RoundLog round : rounds) {
				names.addAll(round.metrics().keySet());
			}
		}
		StringBuilder header = new StringBuilder("| 탐색 | 배속 | 판정 | 왜곡 |");
		names.forEach(name -> header.append(" ").append(name).append(" (평균 / 최대) |"));
		header.append(" 이유 |");
		out.println(header);
		StringBuilder divider = new StringBuilder("|---|---|---|---|");
		names.forEach(name -> divider.append("---|"));
		divider.append("---|");
		out.println(divider);
		int exploration = 0;
		for (List<Evaluation.RoundLog> rounds : evaluation.explorations()) {
			exploration++;
			for (Evaluation.RoundLog round : rounds) {
				List<String> cells = new ArrayList<>();
				cells.add(Integer.toString(exploration));
				cells.add(String.format("%.2f", round.speed()));
				cells.add(verdictName(round.verdict()));
				cells.add(String.format("%.1f%%", round.distortion() * 100));
				for (String name : names) {
					List<Double> stats = round.metrics().get(name);
					cells.add(stats == null ? "-" : String.format("%.1f / %.1f", stats.get(0), stats.get(1)));
				}
				cells.add(round.reason() == null ? "" : cell(round.reason()));
				StringBuilder row = new StringBuilder("|");
				for (String cell : cells) {
					// 빈 칸(이유 없음)도 한 칸으로 둔다. 칸마다 앞뒤에 공백을 넣으면 빈 칸이 두 칸이 된다
					row.append(cell.isEmpty() ? " " : " " + cell + " ").append("|");
				}
				out.println(row);
			}
		}
		out.println();
	}

	/** 라운드의 판정 이름을 표에 쓸 한국어로 바꾼다. 모르는 값이면 그 글자 그대로 둔다. */
	private static String verdictName(String verdict) {
		return switch (verdict) {
			case "HELD" -> "버팀";
			case "OVERLOADED" -> "과부하";
			case "DISTORTED" -> "왜곡";
			default -> verdict;
		};
	}

	/** 되풀이한 평가의 상한 범위 칸. 되풀이하지 않았으면 "한 번"이다. */
	private static String rangeCell(CeilingRange range) {
		if (range == null) {
			return "한 번";
		}
		return String.format("%.2f / %.2f / %.2f (%d번)", range.min(), range.median(), range.max(), range.count());
	}

	/** 종 DB를 함께 튼 평가의 종 DB 수위 칸. 함께 틀지 않았으면 "-"이다. */
	private static String replicaLevelCell(Evaluation evaluation) {
		Evaluation.Replica replica = evaluation.replica();
		if (replica == null) {
			return "-";
		}
		return String.format("%.0f%%", replica.level(evaluation.low(), evaluation.high()).level() * 100);
	}

	private static String stat(RunRecord.Stat s) {
		return s == null ? "-" : String.format("%.1f / %.1f", s.mean(), s.max());
	}

	private static String ms(double micros) {
		return String.format("%.3fms", micros / 1e3);
	}

	private static String change(double before, double after) {
		return before <= 0 ? "" : String.format("%+.1f%%", (after / before - 1) * 100);
	}

	/** 표의 칸 안에 들어갈 모양. 세로선과 역따옴표가 표를 깨지 않게 한다. */
	private static void oneSided(PrintWriter out, String label, Map<String, TraceComparison.Side> shapes, int top) {
		shapes.entrySet().stream()
				.sorted((x, y) -> Long.compare(y.getValue().count(), x.getValue().count()))
				.limit(top)
				.forEach(e -> out.printf("| %s | `%s` | %,d | %,d |%n", label, cell(e.getKey()), e.getValue().count(),
						e.getValue().errors()));
	}

	private static String cell(String shape) {
		String s = shape.length() > 100 ? shape.substring(0, 97) + "..." : shape;
		return s.replace("|", "\\|").replace("`", "'");
	}
}
