package io.dbreplay.explore;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.dbreplay.convert.SqlWords;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;
import io.dbreplay.trace.TraceWriter;

/**
 * 긴 녹화에서 문장이 가장 많은 구간을 찾고 잘라 낸다. 원문은 "업무 피크 시간의 실제 트래픽"으로 탐색하고, 그림 1의 용량 탐색에
 * "트래픽 피크 구간 분석"을 따로 둔다. 그림 9와 11의 녹화는 8~12시간이다. 사용자가 피크를 짐작해 녹화를 끊지 않아도 되게 한다(#130).
 *
 * <p>초 단위로 세고, 정해진 길이의 창을 1초씩 밀어 문장 수가 가장 많은 곳을 고른다. 같으면 앞의 것이다.
 */
public final class PeakWindow {

	/**
	 * @param startMicros 구간의 시작(포함)
	 * @param endMicros   구간의 끝(제외)
	 * @param statements  그 구간에서 시작한 문장 수
	 * @param reads       그 가운데 읽기
	 * @param writes      그 가운데 쓰기
	 * @param spanMicros  녹화 전체의 길이(첫 문장부터 마지막 문장까지)
	 * @param total       녹화 전체의 문장 수
	 */
	public record Peak(long startMicros, long endMicros, long statements, long reads, long writes, long spanMicros, long total) {

		public double qps() {
			return statements / ((endMicros - startMicros) / 1e6);
		}

		/** 녹화 전체의 평균 초당 문장 수. */
		public double averageQps() {
			return spanMicros <= 0 ? total : total / (spanMicros / 1e6);
		}
	}

	/**
	 * @param written 잘라 낸 파일에 쓴 줄 수(연결 종료 줄 포함)
	 * @param dropped 구간 앞에서 시작한 트랜잭션이라 뺀 문장 수
	 */
	public record Cut(long written, long dropped) {
	}

	private PeakWindow() {
	}

	/** 창의 길이가 녹화보다 길면 녹화 전체가 구간이다. */
	public static Peak find(TraceReader trace, long windowMicros) throws IOException {
		if (windowMicros < 1_000_000) {
			throw new IllegalArgumentException("구간은 1초 이상이어야 한다");
		}
		Map<Long, long[]> bySecond = new HashMap<>();
		long first = Long.MAX_VALUE;
		long last = Long.MIN_VALUE;
		long total = 0;
		for (TraceRecord r; (r = trace.next()) != null;) {
			if (r.endsSession()) {
				continue;
			}
			total++;
			first = Math.min(first, r.startMicros());
			last = Math.max(last, r.startMicros());
			long[] c = bySecond.computeIfAbsent(Math.floorDiv(r.startMicros(), 1_000_000L), k -> new long[3]);
			c[0]++;
			if (SqlWords.reads(r.sql())) {
				c[1]++;
			}
			else if (SqlWords.writes(r.sql())) {
				c[2]++;
			}
		}
		if (total == 0) {
			throw new IllegalArgumentException("트래픽 파일에 문장이 없다");
		}
		long firstSecond = Math.floorDiv(first, 1_000_000L);
		int seconds = (int) (Math.floorDiv(last, 1_000_000L) - firstSecond + 1);
		int window = (int) Math.min(seconds, Math.max(1, windowMicros / 1_000_000L));
		long[][] counts = new long[seconds][];
		for (int i = 0; i < seconds; i++) {
			counts[i] = bySecond.getOrDefault(firstSecond + i, new long[3]);
		}
		long[] sum = new long[3];
		for (int i = 0; i < window; i++) {
			add(sum, counts[i], 1);
		}
		long[] best = sum.clone();
		int bestStart = 0;
		for (int start = 1; start + window <= seconds; start++) {
			add(sum, counts[start - 1], -1);
			add(sum, counts[start + window - 1], 1);
			if (sum[0] > best[0]) {
				best = sum.clone();
				bestStart = start;
			}
		}
		long startMicros = (firstSecond + bestStart) * 1_000_000L;
		return new Peak(startMicros, startMicros + window * 1_000_000L, best[0], best[1], best[2], last - first, total);
	}

	private static void add(long[] sum, long[] c, int sign) {
		for (int i = 0; i < 3; i++) {
			sum[i] += sign * c[i];
		}
	}

	/**
	 * 구간 안에서 시작한 줄만 쓴다. 구간 앞에서 시작한 트랜잭션의 문장은 구간 안에 있어도 뺀다. 첫 문장 없이 트랜잭션의 중간부터 틀면
	 * 원본과 다른 일을 하게 된다. 변환기가 녹화의 머리에서 잘린 트랜잭션을 버리는 것과 같은 까닭이다.
	 */
	public static Cut cut(TraceReader in, TraceWriter out, long startMicros, long endMicros) throws IOException {
		Set<String> startedBefore = new HashSet<>();
		long written = 0;
		long dropped = 0;
		for (TraceRecord r; (r = in.next()) != null;) {
			String txn = r.txn() == null ? null : r.session() + "/" + r.txn();
			if (r.startMicros() < startMicros) {
				if (txn != null) {
					startedBefore.add(txn);
				}
				continue;
			}
			if (r.startMicros() >= endMicros) {
				continue;
			}
			if (txn != null && startedBefore.contains(txn)) {
				dropped++;
				continue;
			}
			out.write(r);
			written++;
		}
		return new Cut(written, dropped);
	}
}
