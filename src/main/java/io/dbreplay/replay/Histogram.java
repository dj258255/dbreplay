package io.dbreplay.replay;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * 마이크로초 값의 분포. 값을 다 들고 있지 않고 구간별 개수만 센다.
 *
 * <p>64 미만은 값 그대로, 그 위는 2의 거듭제곱 구간을 32등분한다. 분위수의 오차는 약 3%다.
 * 여러 스레드가 동시에 기록해도 된다.
 */
public final class Histogram {

	private static final int LINEAR = 64;
	private static final int SUB_BITS = 5;
	private static final int SUB = 1 << SUB_BITS;

	private final AtomicLongArray counts = new AtomicLongArray(LINEAR + (63 - 6 + 1) * SUB);
	private final LongAdder total = new LongAdder();
	private volatile long max;

	public void record(long micros) {
		long v = Math.max(0, micros);
		counts.incrementAndGet(indexOf(v));
		total.increment();
		if (v > max) {
			synchronized (this) {
				if (v > max) {
					max = v;
				}
			}
		}
	}

	public long count() {
		return total.sum();
	}

	public long max() {
		return max;
	}

	/** q는 0과 1 사이. 그 분위수가 속한 구간의 아래 끝을 돌려준다. */
	public long quantile(double q) {
		long n = count();
		if (n == 0) {
			return 0;
		}
		long rank = Math.max(1, (long) Math.ceil(q * n));
		long seen = 0;
		for (int i = 0; i < counts.length(); i++) {
			seen += counts.get(i);
			if (seen >= rank) {
				return Math.min(lowerBound(i), max);
			}
		}
		return max;
	}

	private static int indexOf(long v) {
		if (v < LINEAR) {
			return (int) v;
		}
		int exponent = 63 - Long.numberOfLeadingZeros(v);
		int sub = (int) ((v >>> (exponent - SUB_BITS)) & (SUB - 1));
		return LINEAR + (exponent - 6) * SUB + sub;
	}

	private static long lowerBound(int index) {
		if (index < LINEAR) {
			return index;
		}
		int exponent = (index - LINEAR) / SUB + 6;
		long sub = (index - LINEAR) % SUB;
		return (1L << exponent) + (sub << (exponent - SUB_BITS));
	}
}
