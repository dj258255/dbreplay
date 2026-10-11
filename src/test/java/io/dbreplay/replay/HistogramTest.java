package io.dbreplay.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HistogramTest {

	@Test
	void smallValuesAreExact() {
		Histogram h = new Histogram();
		for (long v : new long[] {3, 7, 7, 40, 63}) {
			h.record(v);
		}
		assertEquals(7, h.quantile(0.5));
		assertEquals(63, h.quantile(1.0));
		assertEquals(63, h.max());
	}

	@Test
	void quantilesStayWithinThreePercent() {
		Histogram h = new Histogram();
		for (long v = 1; v <= 100_000; v++) {
			h.record(v);
		}
		assertWithin(50_000, h.quantile(0.5));
		assertWithin(99_000, h.quantile(0.99));
		assertEquals(100_000, h.max());
		assertEquals(100_000, h.count());
	}

	@Test
	void emptyHistogramAnswersZero() {
		assertEquals(0, new Histogram().quantile(0.99));
	}

	private static void assertWithin(long expected, long actual) {
		assertTrue(Math.abs(actual - expected) <= expected * 0.035, expected + " 근처여야 하는데 " + actual);
	}
}
