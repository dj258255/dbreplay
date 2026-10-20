package io.dbreplay.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.dbreplay.trace.TraceHeader;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;
import io.dbreplay.trace.TraceWriter;

class PeakWindowTest {

	private static final long S = 1_000_000;

	/**
	 * 30초 녹화. 0~10초와 20~30초는 초당 1문장, 10~20초는 초당 10문장이다. 9.5초에 시작해 10.5초에 끝나는 트랜잭션 하나가 걸쳐 있다.
	 */
	private static String recording() throws IOException {
		List<TraceRecord> records = new ArrayList<>();
		for (int sec = 0; sec < 30; sec++) {
			int n = sec >= 10 && sec < 20 ? 10 : 1;
			for (int i = 0; i < n; i++) {
				records.add(new TraceRecord("a", 100 * S + sec * S + i * 1_000, 50, "SELECT " + i, null, 0, null, null, null));
			}
		}
		records.add(new TraceRecord("b", 100 * S + 9 * S + 500_000, 50, "BEGIN", null, 0, null, 7L, null));
		records.add(new TraceRecord("b", 100 * S + 9 * S + 600_000, 50, "UPDATE t SET c = 1", null, 0, null, 7L, null));
		records.add(new TraceRecord("b", 100 * S + 10 * S + 400_000, 50, "UPDATE t SET c = 2", null, 0, null, 7L, null));
		records.add(new TraceRecord("b", 100 * S + 10 * S + 500_000, 50, "COMMIT", null, 0, null, 7L, null));
		records.sort((x, y) -> Long.compare(x.startMicros(), y.startMicros()));
		StringWriter out = new StringWriter();
		TraceWriter w = new TraceWriter(out, TraceHeader.of("mysql", "test"));
		for (TraceRecord r : records) {
			w.write(r);
		}
		return out.toString();
	}

	@Test
	void findsTheBusiestWindow() throws IOException {
		PeakWindow.Peak p = PeakWindow.find(new TraceReader(new StringReader(recording())), 10 * S);

		assertEquals(110 * S, p.startMicros());
		assertEquals(120 * S, p.endMicros());
		assertEquals(102, p.statements(), "초당 10문장 10초에 걸친 트랜잭션의 둘");
		assertEquals(10.2, p.qps(), 1e-9);
		assertEquals(124, p.total(), "10 + 100 + 10 + 트랜잭션 4");
	}

	/** 잘라 낸 파일의 첫 줄은 구간 뒤에 있고, 구간 앞에서 시작한 트랜잭션의 문장은 빠진다(#130). */
	@Test
	void cutsTheWindowAndDropsTransactionsThatStartedBefore() throws IOException {
		StringWriter out = new StringWriter();
		PeakWindow.Cut cut = PeakWindow.cut(new TraceReader(new StringReader(recording())),
				new TraceWriter(out, TraceHeader.of("mysql", "test")), 110 * S, 120 * S);

		assertEquals(100, cut.written());
		assertEquals(2, cut.dropped());
		TraceReader back = new TraceReader(new StringReader(out.toString()));
		long first = Long.MAX_VALUE;
		int count = 0;
		for (TraceRecord r; (r = back.next()) != null; count++) {
			first = Math.min(first, r.startMicros());
			assertTrue(!"b".equals(r.session()), "걸친 트랜잭션의 문장이 남았다: " + r.sql());
		}
		assertEquals(100, count);
		assertTrue(first >= 110 * S);
	}

	/** 창이 녹화보다 길면 녹화 전체다. */
	@Test
	void usesTheWholeRecordingWhenTheWindowIsLonger() throws IOException {
		PeakWindow.Peak p = PeakWindow.find(new TraceReader(new StringReader(recording())), 3_600 * S);

		assertEquals(100 * S, p.startMicros());
		assertEquals(124, p.statements());
	}
}
