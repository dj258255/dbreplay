package io.dbreplay.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import io.dbreplay.replay.Histogram;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;

/**
 * 두 트래픽 파일을 SQL 모양별로 비교한다. 실행 시간, 읽은 행 수, 오류를 본다.
 *
 * <p>둘 다 서버 로그에서 만든 파일이어야 실행 시간을 같은 자로 잰 것이 된다. 변경 전후를 비교하려면
 * 재생할 때마다 대상의 로그를 켜고, 그 로그를 변환한 파일 둘을 넣는다.
 *
 * <p>읽은 행 수와 오류는 Oracle Database Replay가 재생이 녹화와 달라진 곳을 보고하는 방식에서 가져왔다.
 * 실행 시간이 비슷해도 읽은 행 수가 크게 늘었으면 실행 계획이 바뀐 것이고, 새로 생긴 오류는 호환성 문제다.
 */
public final class TraceComparison {

	/** 한쪽 파일에서 한 모양이 보인 모습. */
	public static final class Side {
		private long count;
		private long totalMicros;
		private long withRows;
		private long rowsExamined;
		private final Histogram durations = new Histogram();
		private final Map<Integer, Long> errors = new TreeMap<>();

		public long count() {
			return count;
		}

		public long errors() {
			return errors.values().stream().mapToLong(Long::longValue).sum();
		}

		/** 오류 번호별 횟수. */
		public Map<Integer, Long> errorsByCode() {
			return errors;
		}

		public long totalMicros() {
			return totalMicros;
		}

		public double meanMicros() {
			return count == 0 ? 0 : (double) totalMicros / count;
		}

		public long quantile(double q) {
			return durations.quantile(q);
		}

		/** 읽은 행 수를 아는 문장이 하나라도 있는가. PostgreSQL의 로그에는 없다. */
		public boolean knowsRows() {
			return withRows > 0;
		}

		public double meanRowsExamined() {
			return withRows == 0 ? 0 : (double) rowsExamined / withRows;
		}
	}

	/** 양쪽에 다 있는 모양 하나. */
	public record Row(String shape, Side before, Side after) {

		/** 평균 실행 시간이 몇 배가 됐는가. 1보다 크면 나빠진 것이다. */
		public double ratio() {
			return Math.max(after.meanMicros(), 1) / Math.max(before.meanMicros(), 1);
		}

		/** 문장 하나가 읽은 행 수의 평균이 몇 배가 됐는가. 양쪽 다 행 수를 알 때만 뜻이 있다. */
		public double rowsRatio() {
			return Math.max(after.meanRowsExamined(), 1) / Math.max(before.meanRowsExamined(), 1);
		}

		/** 뒤에서만 난 오류 번호들. 번호가 바뀐 것도 여기에 든다. */
		public Set<Integer> newErrors() {
			Set<Integer> codes = new TreeSet<>(after.errorsByCode().keySet());
			codes.removeAll(before.errorsByCode().keySet());
			return codes;
		}

		/** 앞에서만 난 오류 번호들. */
		public Set<Integer> goneErrors() {
			Set<Integer> codes = new TreeSet<>(before.errorsByCode().keySet());
			codes.removeAll(after.errorsByCode().keySet());
			return codes;
		}
	}

	private final List<Row> rows = new ArrayList<>();
	private final Map<String, Side> onlyBefore = new LinkedHashMap<>();
	private final Map<String, Side> onlyAfter = new LinkedHashMap<>();
	private final Side totalBefore = new Side();
	private final Side totalAfter = new Side();

	private TraceComparison() {
	}

	public static TraceComparison of(TraceReader before, TraceReader after) throws IOException {
		TraceComparison c = new TraceComparison();
		Map<String, Side> b = collect(before, c.totalBefore);
		Map<String, Side> a = collect(after, c.totalAfter);
		b.forEach((shape, side) -> {
			Side other = a.remove(shape);
			if (other == null) {
				c.onlyBefore.put(shape, side);
			}
			else {
				c.rows.add(new Row(shape, side, other));
			}
		});
		c.onlyAfter.putAll(a);
		return c;
	}

	private static Map<String, Side> collect(TraceReader trace, Side total) throws IOException {
		Map<String, Side> byShape = new LinkedHashMap<>();
		for (TraceRecord r; (r = trace.next()) != null;) {
			if (r.endsSession()) {
				continue;
			}
			for (Side side : List.of(byShape.computeIfAbsent(SqlShape.of(r.sql()), k -> new Side()), total)) {
				side.count++;
				side.totalMicros += r.durationMicros();
				side.durations.record(r.durationMicros());
				if (r.error() != null && r.error() != 0) {
					side.errors.merge(r.error(), 1L, Long::sum);
				}
				if (r.rowsExamined() != null) {
					side.withRows++;
					side.rowsExamined += r.rowsExamined();
				}
			}
		}
		return byShape;
	}

	public Side totalBefore() {
		return totalBefore;
	}

	public Side totalAfter() {
		return totalAfter;
	}

	public Map<String, Side> onlyBefore() {
		return onlyBefore;
	}

	public Map<String, Side> onlyAfter() {
		return onlyAfter;
	}

	/** 양쪽에서 minCount번 이상 실행된 모양들. 실행 시간을 많이 쓴 순서다. */
	public List<Row> rows(long minCount) {
		return rows.stream()
				.filter(r -> r.before().count() >= minCount && r.after().count() >= minCount)
				.sorted(Comparator.comparingLong((Row r) -> r.before().totalMicros() + r.after().totalMicros()).reversed())
				.toList();
	}

	/** 평균 실행 시간이 가장 많이 늘어난 모양들. 늘어난 것이 없으면 비어 있다. */
	public List<Row> worst(int n, long minCount) {
		return rows(minCount).stream().filter(r -> r.ratio() > 1)
				.sorted(Comparator.comparingDouble(Row::ratio).reversed()).limit(n).toList();
	}

	/** 평균 실행 시간이 가장 많이 줄어든 모양들. */
	public List<Row> best(int n, long minCount) {
		return rows(minCount).stream().filter(r -> r.ratio() < 1)
				.sorted(Comparator.comparingDouble(Row::ratio)).limit(n).toList();
	}

	/**
	 * 문장 하나가 읽는 행 수가 minRatio배 이상으로 늘어난 모양들. 가장 많이 늘어난 것부터다.
	 * 양쪽 다 행 수를 아는 모양만 본다.
	 */
	public List<Row> moreRowsExamined(int n, long minCount, double minRatio) {
		return rows(minCount).stream()
				.filter(r -> r.before().knowsRows() && r.after().knowsRows() && r.rowsRatio() >= minRatio)
				.sorted(Comparator.comparingDouble(Row::rowsRatio).reversed()).limit(n).toList();
	}

	/** 오류 번호의 구성이 달라진 모양들. 횟수 제한 없이 전부 본다. 한 번 난 오류도 호환성 문제일 수 있다. */
	public List<Row> errorChanges() {
		return rows.stream().filter(r -> !r.newErrors().isEmpty() || !r.goneErrors().isEmpty())
				.sorted(Comparator.comparingLong((Row r) -> r.after().errors()).reversed()).toList();
	}
}
