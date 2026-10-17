package io.dbreplay.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.dbreplay.convert.HeadPolicy;
import io.dbreplay.convert.PcapBuilder;
import io.dbreplay.convert.RecordBuffer;
import io.dbreplay.convert.ScopeFilter;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;

class MysqlCaptureTest {

	private static final long T = 1_791_676_800_000_000L;

	/** MySQL 패킷 하나: 길이 3바이트, 순번, 본문. */
	private static byte[] packet(int seq, byte[]... parts) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		for (byte[] p : parts) {
			body.writeBytes(p);
		}
		int n = body.size();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(n & 0xff);
		out.write((n >> 8) & 0xff);
		out.write((n >> 16) & 0xff);
		out.write(seq);
		out.writeBytes(body.toByteArray());
		return out.toByteArray();
	}

	private static byte[] b(int... values) {
		byte[] out = new byte[values.length];
		for (int i = 0; i < values.length; i++) {
			out[i] = (byte) values[i];
		}
		return out;
	}

	private static byte[] s(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] le32(int v) {
		return b(v, v >> 8, v >> 16, v >> 24);
	}

	private static final byte[] OK = packet(1, b(0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00));

	/** 로그인부터 끝까지 한 연결. 능력: PROTOCOL_41, SECURE_CONNECTION, CONNECT_WITH_DB, DEPRECATE_EOF. */
	private static PcapBuilder session(PcapBuilder pcap, int port) {
		int lower = 0x8208;
		pcap.open(T, port)
				.server(T + 10, packet(0, b(0x0a), s("8.4.11"), b(0), le32(42), new byte[8], b(0), b(lower, lower >> 8), b(0xff),
						b(0x02, 0x00), b(0x00, 0x01)))
				.client(T + 20, packet(1, le32(lower | 0x01000000), le32(1 << 24), b(0xff), new byte[23], s("app"), b(0), b(0), s("shop"), b(0)))
				.server(T + 30, packet(2, b(0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00)));
		return pcap;
	}

	private static List<TraceRecord> convert(PcapBuilder pcap, String[] losses) throws IOException {
		MysqlCapture capture = new MysqlCapture(pcap.stream(), 3306);
		StringWriter out = new StringWriter();
		try (RecordBuffer buffer = new RecordBuffer()) {
			SlowLogConverter.convert(capture, "pcap", out, HeadPolicy::byDefault, buffer, null, null, ScopeFilter.all());
		}
		losses[0] = capture.losses();
		List<TraceRecord> records = new ArrayList<>();
		try (TraceReader reader = new TraceReader(new StringReader(out.toString()))) {
			assertEquals("pcap", reader.header().source());
			for (TraceRecord r; (r = reader.next()) != null;) {
				records.add(r);
			}
		}
		return records;
	}

	/**
	 * 단순 질의, 결과 집합, 준비한 문장의 실행(인자를 끼워 넣는다), 오류, 끝을 슬로우 로그와 같은 레코드로 낸다 (#79).
	 * 걸린 시간은 요청부터 마지막 응답까지이고, 락 시간과 읽은 행 수는 모른다고 비워 둔다.
	 */
	@Test
	void decodesQueriesPreparedStatementsAndErrors() throws IOException {
		PcapBuilder pcap = session(new PcapBuilder(3306), 50001)
				.client(T + 1_000, packet(0, b(0x03), s("BEGIN"))).server(T + 1_100, OK)
				.client(T + 2_000, packet(0, b(0x03), s("SELECT c FROM t WHERE id = 1")))
				.server(T + 2_050, packet(1, b(0x01)))
				.server(T + 2_060, packet(2, s("column-definition")))
				.server(T + 2_070, packet(3, b(0x01), s("x")))
				.server(T + 2_100, packet(4, b(0xfe, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00)))
				.client(T + 3_000, packet(0, b(0x16), s("UPDATE t SET v = ? WHERE id = ?")))
				.server(T + 3_010, packet(1, b(0x00), le32(7), b(0, 0), b(2, 0), b(0), b(0, 0)))
				.server(T + 3_020, packet(2, s("param-1")))
				.server(T + 3_030, packet(3, s("param-2")))
				.client(T + 4_000, packet(0, b(0x17), le32(7), b(0), le32(1), b(0x00), b(1), b(0x0f, 0x00, 0x03, 0x00),
						b(4), s("it's"), le32(5)))
				.server(T + 4_100, OK)
				.client(T + 5_000, packet(0, b(0x03), s("COMMIT"))).server(T + 5_100, OK)
				.client(T + 6_000, packet(0, b(0x03), s("SELEC oops")))
				.server(T + 6_100, packet(1, b(0xff, 0x28, 0x04), s("#42000syntax")))
				.client(T + 7_000, packet(0, b(0x01)));
		String[] losses = new String[1];
		List<TraceRecord> records = convert(pcap, losses);

		assertEquals(List.of("BEGIN", "SELECT c FROM t WHERE id = 1", "UPDATE t SET v = 'it''s' WHERE id = 5", "COMMIT", "SELEC oops"),
				records.stream().filter(r -> r.sql() != null).map(TraceRecord::sql).toList());
		TraceRecord select = records.get(1);
		assertEquals("42", select.session(), "연결 번호는 서버가 인사에서 알려 준 것이다");
		assertEquals("shop", select.db());
		assertEquals(100, select.durationMicros());
		assertEquals(1L, select.rowsSent());
		assertNull(select.rowsExamined(), "읽은 행 수는 프로토콜에 없다");
		assertNull(select.lockMicros());
		assertEquals(1L, select.txn());
		assertEquals(1L, records.get(3).txn(), "COMMIT까지 한 트랜잭션이다");
		assertEquals(1064, records.get(4).error());
		assertTrue(records.getLast().endsSession());
	}

	/** 시작을 보지 못한 연결과 조각을 놓친 연결은 해석하지 않고 센다. */
	@Test
	void countsConnectionsItCannotRead() throws IOException {
		PcapBuilder pcap = new PcapBuilder(3306);
		// 시작(SYN) 없이 데이터만 보인 연결
		pcap.client(T, packet(0, b(0x03), s("SELECT 1")));
		session(pcap, 50002).skipClientBytes(10).client(T + 1_000, packet(0, b(0x03), s("SELECT 2"))).server(T + 1_100, OK);
		// 놓친 뒤에도 그 연결의 조각이 계속 온다. 시작을 보지 못한 연결로 다시 세지 않는다(실험 19)
		pcap.client(T + 2_000, packet(0, b(0x03), s("SELECT 3"))).server(T + 2_100, OK);
		String[] losses = new String[1];
		List<TraceRecord> records = convert(pcap, losses);
		assertTrue(records.stream().noneMatch(r -> "SELECT 2".equals(r.sql())), "놓친 뒤의 문장을 지어내지 않는다");
		assertTrue(losses[0].startsWith("시작을 보지 못한 연결 1, 조각을 놓친 연결 1"), losses[0]);
	}

	@Test
	void inlinesValuesOutsideQuotesAndComments() {
		assertEquals("SELECT '?', 1 /* ? */ FROM t WHERE a = 'x' AND b = ?",
				BinaryValues.inline("SELECT '?', ? /* ? */ FROM t WHERE a = ? AND b = ?", new String[] {"1", "'x'"}));
	}
}
