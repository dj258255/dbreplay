package io.dbreplay.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.dbreplay.replay.Histogram;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;

/**
 * 두 트래픽 파일을 SQL 모양별로 비교한다.
 *
 * <p>둘 다 서버 로그에서 만든 파일이어야 실행 시간을 같은 자로 잰 것이 된다. 변경 전후를 비교하려면
 * 재생할 때마다 대상의 로그를 켜고, 그 로그를 변환한 파일 둘을 넣는다.
 */
public final class TraceComparison {

	/** 한쪽 파일에서 한 모양이 보인 모습. */
	public static final class Side {
		private long count;
		private long errors;
		private long totalMicros;
		private final Histogram durations = new Histogram();

		public long count() {
			return count;
		}

		public long errors() {
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
	}

	/** 양쪽에 다 있는 모양 하나. */
	public record Row(String shape, Side before, Side after) {

		/** 평균 실행 시간이 몇 배가 됐는가. 1보다 크면 나빠진 것이다. */
		public double ratio() {
			return Math.max(after.meanMicros(), 1) / Math.max(before.meanMicros(), 1);
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
					side.errors++;
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
}
