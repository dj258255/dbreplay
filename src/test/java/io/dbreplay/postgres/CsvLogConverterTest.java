package io.dbreplay.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.dbreplay.convert.ConvertSummary;
import io.dbreplay.convert.HeadPolicy;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;

class CsvLogConverterTest {

	private record Converted(ConvertSummary summary, List<TraceRecord> records) {

		List<String> sqls() {
			return records.stream().map(TraceRecord::sql).toList();
		}
	}

	private static Converted convert(String csv) throws IOException {
		StringWriter out = new StringWriter();
		ConvertSummary summary = CsvLogConverter.convert(new StringReader(csv), out, HeadPolicy::byDefault);
		List<TraceRecord> records = new ArrayList<>();
		try (TraceReader reader = new TraceReader(new StringReader(out.toString()))) {
			assertEquals("postgresql", reader.header().engine());
			for (TraceRecord r; (r = reader.next()) != null;) {
				records.add(r);
			}
		}
		return new Converted(summary, records);
	}

	/** csvlog 한 행. 16판의 칸 순서대로 필요한 칸만 채운다. */
	private static String row(String time, String session, String severity, String message, String detail, String query) {
		String[] columns = new String[26];
		columns[0] = "2026-10-11 " + time + " UTC";
		columns[1] = "postgres";
		columns[2] = "shop";
		columns[3] = "953";
		columns[5] = session;
		columns[11] = severity;
		columns[12] = severity.equals("ERROR") ? "42P01" : "00000";
		columns[13] = message;
		columns[14] = detail;
		columns[19] = query;
		return Arrays.stream(columns)
				.map(c -> c == null ? "" : "\"" + c.replace("\"", "\"\"") + "\"")
				.collect(Collectors.joining(",")) + "\n";
	}

	private static String statement(String time, String session, String durationMs, String sql) {
		return row(time, session, "LOG", "duration: " + durationMs + " ms  statement: " + sql, null, null);
	}

	@Test
	void groupsATransactionAndOrdersByStartTime() throws IOException {
		Converted c = convert(
				statement("00:00:01.000", "a.1", "0.045", "BEGIN;")
				+ statement("00:00:01.002", "a.1", "1.694", "UPDATE accounts SET balance = balance - 10 WHERE id = 1;")
				// 0.6초 걸린 문장은 0.7초에 찍히지만 0.1초에 시작했다. 그 사이에 다른 연결의 문장이 먼저 찍힌다
				+ statement("00:00:01.300", "b.2", "0.020", "SELECT 1")
				+ statement("00:00:01.700", "a.1", "600.000", "SELECT pg_sleep(0.6)")
				+ statement("00:00:01.702", "a.1", "1.401", "COMMIT")
				+ statement("00:00:01.800", "a.1", "0.092", "SELECT balance FROM accounts WHERE id = 1")
				+ row("00:00:01.900", "a.1", "LOG", "disconnection: session time: 0:00:01.000 user=postgres database=shop", null, null));

		assertEquals(List.of("BEGIN", "UPDATE accounts SET balance = balance - 10 WHERE id = 1", "SELECT pg_sleep(0.6)",
				"SELECT 1", "COMMIT", "SELECT balance FROM accounts WHERE id = 1"), c.sqls().subList(0, 6));
		List<TraceRecord> a = c.records().stream().filter(r -> r.session().equals("a.1")).toList();
		assertEquals(Arrays.asList(1L, 1L, 1L, 1L, null, null), a.stream().map(TraceRecord::txn).toList());
		assertTrue(a.getLast().endsSession());

		TraceRecord update = a.get(1);
		assertEquals(1_694, update.durationMicros());
		assertEquals(1_791_676_801_002_000L - 1_694, update.startMicros());
		assertEquals("shop", update.db());
		assertEquals(0, update.error());
		assertNull(update.lockMicros(), "PostgreSQL 로그에는 락 대기 시간이 없다");
		assertEquals(1, c.summary().closes());
	}

	@Test
	void inlinesParametersOfExtendedProtocolAndDropsParseAndBind() throws IOException {
		Converted c = convert(
				row("00:00:01.000", "a.1", "LOG", "duration: 0.100 ms  parse P0_3: UPDATE t SET v = $1 WHERE id = $2", null, null)
				+ row("00:00:01.001", "a.1", "LOG", "duration: 0.050 ms  bind P0_3: UPDATE t SET v = $1 WHERE id = $2",
						"parameters: $1 = 'it''s', $2 = '42'", null)
				+ row("00:00:01.002", "a.1", "LOG", "duration: 0.300 ms  execute P0_3: UPDATE t SET v = $1 WHERE id = $2",
						"parameters: $1 = 'it''s', $2 = '42'", null));

		assertEquals(List.of("UPDATE t SET v = 'it''s' WHERE id = '42'"), c.sqls());
		assertEquals(2, c.summary().ignored());
	}

	@Test
	void keepsAFailedStatementInsideItsTransaction() throws IOException {
		Converted c = convert(
				statement("00:00:01.000", "a.1", "0.020", "BEGIN")
				+ row("00:00:01.001", "a.1", "ERROR", "relation \"no_such_table\" does not exist", null, "SELECT * FROM no_such_table;")
				+ row("00:00:01.002", "a.1", "ERROR", "current transaction is aborted, commands ignored until end of transaction block",
						null, "UPDATE accounts SET balance = 0")
				+ statement("00:00:01.003", "a.1", "0.027", "ROLLBACK"));

		assertEquals(List.of("BEGIN", "SELECT * FROM no_such_table", "UPDATE accounts SET balance = 0", "ROLLBACK"), c.sqls());
		assertEquals(List.of(0, 1, 1, 0), c.records().stream().map(TraceRecord::error).toList());
		assertEquals(List.of(1L, 1L, 1L, 1L), c.records().stream().map(TraceRecord::txn).toList());
	}

	@Test
	void readsStatementsThatSpanLinesAndIgnoresServerMessages() throws IOException {
		Converted c = convert(
				row("00:00:00.500", "a.1", "LOG", "connection authorized: user=postgres database=shop", null, null)
				+ statement("00:00:01.000", "a.1", "0.500", "SELECT a,\n       \"B\"\nFROM t\nWHERE note = 'x, \"y\"'")
				+ "2026-10-11 00:00:02.000 UTC,,,77,,c.9,1,,2026-10-11 00:00:00 UTC,,0,LOG,00000,\"checkpoint starting: time\",,,,,,,,,\"\",\"checkpointer\",,0\n");

		assertEquals(List.of("SELECT a,\n       \"B\"\nFROM t\nWHERE note = 'x, \"y\"'"), c.sqls());
		assertEquals(3, c.summary().entries());
		assertEquals(2, c.summary().ignored());
		assertEquals(0, c.summary().skipped());
	}

	/** 실험 04에서 pgbench를 준비된 문장 방식으로 돌려 받은 실제 csvlog의 앞부분. */
	@Test
	void convertsARealPgbenchLogRecordedWithPreparedStatements() throws IOException {
		Converted c;
		try (var in = new java.io.InputStreamReader(
				CsvLogConverterTest.class.getResourceAsStream("/postgres/exp04-pgbench.csv"), java.nio.charset.StandardCharsets.UTF_8)) {
			StringWriter out = new StringWriter();
			ConvertSummary summary = CsvLogConverter.convert(in, out, HeadPolicy::byDefault);
			List<TraceRecord> records = new ArrayList<>();
			try (TraceReader reader = new TraceReader(new StringReader(out.toString()))) {
				for (TraceRecord r; (r = reader.next()) != null;) {
					records.add(r);
				}
			}
			c = new Converted(summary, records);
		}

		assertEquals(0, c.summary().skipped());
		assertTrue(c.records().size() > 300);
		List<TraceRecord> statements = c.records().stream().filter(r -> !r.endsSession()).toList();
		assertTrue(statements.stream().allMatch(r -> "shop".equals(r.db())));
		assertTrue(statements.stream().noneMatch(r -> r.sql().matches("(?s).*\\$\\d.*")), "인자 자리가 남아 있으면 안 된다");

		// pgbench의 기본 트랜잭션은 BEGIN, UPDATE 셋, SELECT, INSERT, END의 일곱 문장이다
		var sizes = c.records().stream().filter(r -> r.txn() != null)
				.collect(Collectors.groupingBy(r -> r.session() + "/" + r.txn(), Collectors.counting()));
		long complete = sizes.values().stream().filter(n -> n == 7).count();
		assertTrue(complete >= 30, "완결된 트랜잭션 수: " + complete);
		assertTrue(sizes.values().stream().allMatch(n -> n <= 7));
		assertTrue(statements.stream().anyMatch(r -> r.sql().startsWith("UPDATE pgbench_accounts SET abalance = abalance + '")));
	}

	@Test
	void refusesATimeZoneAbbreviationItCannotResolve() {
		String csv = statement("00:00:01.000", "a.1", "0.500", "SELECT 1").replace(" UTC", " KST");

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> convert(csv));
		assertTrue(e.getMessage().contains("log_timezone"));
	}

	@Test
	void inliningLeavesQuotedAndDollarQuotedTextAlone() {
		assertEquals("UPDATE t SET a = '5', b = NULL WHERE id = 'it''s' AND c = '$1' AND d = $$ $1 $$ AND e = $tag$ $2 $tag$ -- $1",
				PgParameters.inline("UPDATE t SET a = $1, b = $2 WHERE id = $10 AND c = '$1' AND d = $$ $1 $$ AND e = $tag$ $2 $tag$ -- $1",
						"parameters: $1 = '5', $2 = NULL, $10 = 'it''s'"));
		assertEquals("SELECT $1", PgParameters.inline("SELECT $1", null));
	}
}
