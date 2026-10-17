package io.dbreplay.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;

class TraceSelectionTest {

	private static final String HEADER = "{\"format\":\"dbreplay-trace\",\"version\":1,\"engine\":\"mysql\",\"source\":\"slow-log\"}\n";

	private static String line(String session, long start, String sql, Long txn) {
		return "{\"session\":\"" + session + "\",\"startMicros\":" + start + ",\"durationMicros\":1,\"sql\":\"" + sql + "\""
				+ (txn == null ? "" : ",\"txn\":" + txn) + "}\n";
	}

	private static final String TRACE = HEADER
			+ line("1", 1, "BEGIN", 1L) + line("1", 2, "SELECT 1", 1L) + line("1", 3, "UPDATE t SET v = 1", 1L) + line("1", 4, "COMMIT", 1L)
			+ line("2", 5, "BEGIN", 1L) + line("2", 6, "SELECT 2", 1L) + line("2", 7, "COMMIT", 1L)
			+ line("3", 8, "SELECT 3", null) + line("3", 9, "INSERT INTO t VALUES (1)", null)
			+ "{\"session\":\"3\",\"startMicros\":10,\"durationMicros\":0,\"close\":true}\n";

	private static List<TraceRecord> read(Predicate<TraceRecord> keep) throws IOException {
		List<TraceRecord> out = new ArrayList<>();
		try (TraceReader reader = new TraceReader(new StringReader(TRACE), keep)) {
			for (TraceRecord r; (r = reader.next()) != null;) {
				out.add(r);
			}
		}
		return out;
	}

	/** 쓰기가 든 트랜잭션은 통째로, 트랜잭션 밖에서는 쓰기 문장만, 연결 종료 줄은 남긴다 (#81). */
	@Test
	void keepsWholeWritingTransactionsAndStandaloneWrites() throws IOException {
		Predicate<TraceRecord> writes;
		try (TraceReader reader = new TraceReader(new StringReader(TRACE))) {
			writes = TraceSelection.writeUnits(reader);
		}
		List<String> kept = read(writes).stream().map(r -> r.sql() == null ? "close" : r.sql()).toList();
		assertEquals(List.of("BEGIN", "SELECT 1", "UPDATE t SET v = 1", "COMMIT", "INSERT INTO t VALUES (1)", "close"), kept);
	}

	/** 조각들을 합치면 전부이고, 한 연결의 문장은 한 조각에만 든다. */
	@Test
	void shardsPartitionBySession() throws IOException {
		int total = read(r -> true).size();
		int sum = 0;
		for (int i = 0; i < 3; i++) {
			List<TraceRecord> part = read(TraceSelection.shard(i, 3));
			sum += part.size();
			for (TraceRecord r : part) {
				assertTrue(read(TraceSelection.shard(i, 3)).stream().anyMatch(o -> o.session().equals(r.session())));
			}
		}
		assertEquals(total, sum);
		assertEquals(read(TraceSelection.shard(0, 2)), read(TraceSelection.shard("1/2")));
		assertThrows(IllegalArgumentException.class, () -> TraceSelection.shard("3/2"));
		assertThrows(IllegalArgumentException.class, () -> TraceSelection.shard("x"));
	}
}
