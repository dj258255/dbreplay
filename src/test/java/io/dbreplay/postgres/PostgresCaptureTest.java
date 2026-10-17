package io.dbreplay.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
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

class PostgresCaptureTest {

	private static final long T = 1_791_676_800_000_000L;

	/** 형식 글자, 길이(자신 포함), 본문. */
	private static byte[] message(char type, byte[]... parts) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		for (byte[] p : parts) {
			body.writeBytes(p);
		}
		ByteBuffer out = ByteBuffer.allocate(5 + body.size());
		out.put((byte) type).putInt(4 + body.size()).put(body.toByteArray());
		return out.array();
	}

	private static byte[] cs(String text) {
		byte[] t = text.getBytes(StandardCharsets.UTF_8);
		byte[] out = new byte[t.length + 1];
		System.arraycopy(t, 0, out, 0, t.length);
		return out;
	}

	private static byte[] i16(int v) {
		return ByteBuffer.allocate(2).putShort((short) v).array();
	}

	private static byte[] i32(int v) {
		return ByteBuffer.allocate(4).putInt(v).array();
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) {
			out.writeBytes(p);
		}
		return out.toByteArray();
	}

	private static byte[] startup() {
		byte[] params = concat(cs("user"), cs("app"), cs("database"), cs("shop"), new byte[1]);
		return concat(i32(8 + params.length), i32(196608), params);
	}

	private static byte[] ready(char status) {
		return message('Z', new byte[] {(byte) status});
	}

	/** 단순 질의와 확장 질의(인자를 끼워 넣는다), 오류, 끝을 csvlog와 같은 레코드로 낸다 (#79). */
	@Test
	void decodesSimpleAndExtendedQueries() throws IOException {
		PcapBuilder pcap = new PcapBuilder(5432).open(T, 50001)
				.client(T + 10, startup())
				.server(T + 20, concat(message('R', i32(0)), message('K', i32(777), i32(1)), ready('I')))
				.client(T + 1_000, message('Q', cs("BEGIN")))
				.server(T + 1_100, concat(message('C', cs("BEGIN")), ready('T')))
				.client(T + 2_000, concat(message('P', cs(""), cs("UPDATE t SET v = $1 WHERE id = $2"), i16(0)),
						message('B', cs(""), cs(""), i16(0), i16(2), i32(4), "it's".getBytes(StandardCharsets.UTF_8), i32(1),
								"5".getBytes(StandardCharsets.UTF_8), i16(0)),
						message('E', cs(""), i32(0)), message('S')))
				.server(T + 2_200, concat(message('1'), message('2'), message('C', cs("UPDATE 1")), ready('T')))
				.client(T + 3_000, message('Q', cs("COMMIT")))
				.server(T + 3_100, concat(message('C', cs("COMMIT")), ready('I')))
				.client(T + 4_000, message('Q', cs("SELEC")))
				.server(T + 4_100, concat(message('E', cs("SERROR"), cs("C42601"), new byte[1]), ready('I')))
				.client(T + 5_000, message('X'));
		PostgresCapture capture = new PostgresCapture(pcap.stream(), 5432);
		StringWriter out = new StringWriter();
		try (RecordBuffer buffer = new RecordBuffer()) {
			CsvLogConverter.convert(capture, out, HeadPolicy::byDefault, buffer, null, null, ScopeFilter.all());
		}
		List<TraceRecord> records = new ArrayList<>();
		try (TraceReader reader = new TraceReader(new StringReader(out.toString()))) {
			assertEquals("pcap", reader.header().source());
			for (TraceRecord r; (r = reader.next()) != null;) {
				records.add(r);
			}
		}
		assertEquals(List.of("BEGIN", "UPDATE t SET v = 'it''s' WHERE id = '5'", "COMMIT", "SELEC"),
				records.stream().filter(r -> r.sql() != null).map(TraceRecord::sql).toList());
		assertEquals("777", records.get(0).session(), "연결 식별자는 백엔드의 프로세스 번호다");
		assertEquals("shop", records.get(0).db());
		assertEquals(200, records.get(1).durationMicros());
		assertEquals(records.get(0).txn(), records.get(2).txn(), "COMMIT까지 한 트랜잭션이다");
		assertEquals(CsvLogConverter.FAILED, records.get(3).error());
		assertTrue(records.getLast().endsSession());
		assertTrue(capture.losses().startsWith("시작을 보지 못한 연결 0, 조각을 놓친 연결 0, 읽지 못한 연결(TLS, 모르는 메시지) 0"));
	}

	/** TLS로 넘어가는 연결은 읽지 않고 센다. */
	@Test
	void countsTlsConnections() throws IOException {
		PcapBuilder pcap = new PcapBuilder(5432).open(T, 50002)
				.client(T + 10, concat(i32(8), i32(80877103)))
				.server(T + 20, new byte[] {'S'})
				.client(T + 30, new byte[] {0x16, 0x03, 0x01, 0x00, 0x05, 1, 2, 3, 4, 5});
		PostgresCapture capture = new PostgresCapture(pcap.stream(), 5432);
		while (capture.next() != null) {
			// 끝까지 읽는다
		}
		assertTrue(capture.losses().contains("읽지 못한 연결(TLS, 모르는 메시지) 1"), capture.losses());
	}
}
