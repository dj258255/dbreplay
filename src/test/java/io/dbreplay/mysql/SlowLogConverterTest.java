package io.dbreplay.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.dbreplay.convert.ConvertSummary;
import io.dbreplay.convert.HeadPolicy;
import io.dbreplay.convert.RecordBuffer;
import io.dbreplay.convert.ScopeFilter;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;

class SlowLogConverterTest {

	private static final Pattern TAG = Pattern.compile("/\\* (\\w+\\.?\\w*)[ \\w]*\\*/");

	private record Converted(ConvertSummary summary, List<TraceRecord> records) {

		Map<String, TraceRecord> byTag() {
			Map<String, TraceRecord> map = new LinkedHashMap<>();
			for (TraceRecord r : records) {
				Matcher m = r.sql() == null ? null : TAG.matcher(r.sql());
				if (m != null && m.find()) {
					map.put(m.group(1), r);
				}
			}
			return map;
		}
	}

	private static Converted convert(Reader log, HeadPolicy policy) throws IOException {
		StringWriter out = new StringWriter();
		ConvertSummary summary = SlowLogConverter.convert(log, out, policy);
		List<TraceRecord> records = new ArrayList<>();
		try (TraceReader reader = new TraceReader(new StringReader(out.toString()))) {
			assertEquals("mysql", reader.header().engine());
			for (TraceRecord r; (r = reader.next()) != null;) {
				records.add(r);
			}
		}
		return new Converted(summary, records);
	}

	private static Reader resource(String name) {
		return new InputStreamReader(SlowLogConverterTest.class.getResourceAsStream(name), StandardCharsets.UTF_8);
	}

	/** 실험 01의 여섯 세션 로그. 기대값은 그때 performance_schema와 대조해 확인한 묶음이다. */
	@Test
	void groupsStatementsIntoTheTransactionsTheServerSaw() throws IOException {
		Converted c = convert(resource("/mysql/exp01-slow.log"), HeadPolicy::byDefault);
		Map<String, TraceRecord> byTag = c.byTag();

		List<List<String>> transactions = List.of(
				List.of("s1.1", "s1.2", "s1.3", "s1.4", "s1.5"),
				List.of("s2.2", "s2.3", "s2.4", "s2.5"),
				List.of("s4.2", "s4.3", "s4.4"),
				List.of("s4.5", "s4.6", "s4.7"),
				List.of("s5.1", "s5.2", "s5.3"),
				List.of("s6.7", "s6.8"));
		Set<String> inTransaction = new TreeSet<>();
		Set<String> seenKeys = new TreeSet<>();
		for (List<String> tags : transactions) {
			TraceRecord first = byTag.get(tags.getFirst());
			assertNotNull(first.txn(), tags.getFirst());
			for (String tag : tags) {
				TraceRecord r = byTag.get(tag);
				assertEquals(first.session(), r.session(), tag);
				assertEquals(first.txn(), r.txn(), tag);
			}
			assertTrue(seenKeys.add(first.session() + "/" + first.txn()), "묶음이 겹친다: " + tags);
			inTransaction.addAll(tags);
		}
		for (Map.Entry<String, TraceRecord> e : byTag.entrySet()) {
			if (!inTransaction.contains(e.getKey())) {
				assertNull(e.getValue().txn(), e.getKey());
			}
		}
	}

	@Test
	void ordersByStartTimeAndKeepsWhatTheLogMeasured() throws IOException {
		Converted c = convert(resource("/mysql/exp01-slow.log"), HeadPolicy::byDefault);
		Map<String, TraceRecord> byTag = c.byTag();

		for (int i = 1; i < c.records().size(); i++) {
			assertTrue(c.records().get(i - 1).startMicros() <= c.records().get(i).startMicros(), "줄 " + i);
		}
		// 로그에는 락을 기다린 UPDATE가 그 락을 푼 COMMIT보다 뒤에 적혀 있다
		TraceRecord blocked = byTag.get("s2.3");
		assertTrue(c.records().indexOf(blocked) < c.records().indexOf(byTag.get("s1.5")));
		assertEquals(407_741, blocked.durationMicros());
		assertEquals(407_191, blocked.lockMicros());

		assertEquals(1146, byTag.get("s6.6").error());
		// s3.4는 기본키로 한 행을 찾는 조회다. 한 행을 읽어 한 행을 보냈다
		assertEquals(1, byTag.get("s3.4").rowsSent());
		assertEquals(1, byTag.get("s3.4").rowsExamined());
		assertEquals(0, byTag.get("s1.2").error());
		assertEquals("shop", byTag.get("s1.2").db());
		// EXECUTE는 값이 채워진 본문으로 남고, PREPARE와 DEALLOCATE는 빠진다
		assertEquals("UPDATE /* s6.2p */ accounts SET balance = balance + 2 WHERE id = 5", byTag.get("s6.2p").sql());
		assertFalse(byTag.containsKey("s6.5"));
		assertTrue(c.records().stream().noneMatch(r -> r.sql() != null && r.sql().startsWith("PREPARE")));

		ConvertSummary s = c.summary();
		assertEquals(3, s.ignored(), "Ping 하나와 PREPARE, DEALLOCATE");
		assertEquals(0, s.skipped());
		assertEquals(0, s.clamped());
		assertEquals(c.records().stream().filter(TraceRecord::endsSession).count(), s.closes());
		assertEquals(s.entries(), s.statements() + s.closes() + s.ignored() + s.droppedHead());
	}

	/** use db; 줄은 로그 파일 기준으로 찍힌다. 세 연결이 섞여도 각 문장의 데이터베이스가 맞아야 한다. */
	@Test
	void tracksTheDefaultDatabaseAcrossInterleavedSessions() throws IOException {
		Map<String, TraceRecord> byTag = convert(resource("/mysql/exp01-use-db.log"), HeadPolicy::byDefault).byTag();

		for (String tag : List.of("a1", "a2", "a3")) {
			assertEquals("shop", byTag.get(tag).db(), tag);
		}
		for (String tag : List.of("b1", "b2", "b3", "b4")) {
			assertEquals("other", byTag.get(tag).db(), tag);
		}
	}

	@Test
	void asksTheHeadPolicyAboutATransactionCutByTheStartOfRecording() throws IOException {
		String log = entry("2026-10-11T00:00:01.000000Z", 7, "0.000100", "UPDATE t SET a = 1 WHERE id = 1")
				+ entry("2026-10-11T00:00:02.000000Z", 7, "0.000100", "ROLLBACK")
				+ entry("2026-10-11T00:00:03.000000Z", 7, "0.000100", "SELECT a FROM t WHERE id = 1")
				+ entry("2026-10-11T00:00:04.000000Z", 7, "0.000100", "ROLLBACK");

		List<HeadPolicy.Head> asked = new ArrayList<>();
		Converted kept = convert(new StringReader(log), head -> {
			asked.add(head);
			return HeadPolicy.Decision.KEEP;
		});
		assertEquals(List.of(new HeadPolicy.Head(1, 1, true)), asked);
		assertEquals(4, kept.records().size());

		Converted dropped = convert(new StringReader(log), head -> HeadPolicy.Decision.DROP);
		assertEquals(List.of("SELECT a FROM t WHERE id = 1", "ROLLBACK"),
				dropped.records().stream().map(TraceRecord::sql).toList());
		assertEquals(2, dropped.summary().droppedHead());
	}

	/** 기본 정책: 롤백으로 끝난 머리는 빼고 커밋으로 끝난 머리는 남긴다. 롤백된 쓰기를 재생에서 커밋하지 않으려는 것이다. */
	@Test
	void byDefaultDropsAHeadThatWasRolledBackAndKeepsOneThatWasCommitted() throws IOException {
		String log = entry("2026-10-11T00:00:01.000000Z", 7, "0.000100", "UPDATE t SET a = 1 WHERE id = 1")
				+ entry("2026-10-11T00:00:02.000000Z", 7, "0.000100", "ROLLBACK")
				+ entry("2026-10-11T00:00:01.500000Z", 8, "0.000100", "UPDATE t SET a = 2 WHERE id = 2")
				+ entry("2026-10-11T00:00:02.500000Z", 8, "0.000100", "COMMIT")
				+ entry("2026-10-11T00:00:03.000000Z", 7, "0.000100", "SELECT a FROM t WHERE id = 1");

		Converted c = convert(new StringReader(log), HeadPolicy::byDefault);

		assertEquals(List.of("UPDATE t SET a = 2 WHERE id = 2", "COMMIT", "SELECT a FROM t WHERE id = 1"),
				c.records().stream().map(TraceRecord::sql).toList());
		assertEquals(2, c.summary().droppedHead(), "롤백된 UPDATE와 그 ROLLBACK");
	}

	/** 녹화하는 동안 이 도구로 지표를 뜨면 그 조회가 로그에 섞인다. 원본의 트래픽이 아니라서 뺀다. */
	@Test
	void leavesOutTheStatementsThisToolSentItself() throws IOException {
		String log = entry("2026-10-11T00:00:01.000000Z", 3, "0.000100", "SELECT 1")
				+ entry("2026-10-11T00:00:02.000000Z", 9, "0.000300", "/* dbreplay:sampler */ SELECT (SELECT VARIABLE_VALUE - 1 FROM performance_schema.global_status)");

		Converted c = convert(new StringReader(log), HeadPolicy::byDefault);
		assertEquals(List.of("SELECT 1"), c.records().stream().map(TraceRecord::sql).toList());
		assertEquals(1, c.summary().ignored());
	}

	/** 표시는 문장의 맨 앞에 있을 때만 본다. 그 글자가 값 안에 든 원본의 문장을 빼면 안 된다. */
	@Test
	void keepsAnApplicationStatementThatOnlyMentionsTheMark() throws IOException {
		String sql = "INSERT INTO notes (body) VALUES ('/* dbreplay:sampler */ 라는 글자를 봤다')";
		Converted c = convert(new StringReader(entry("2026-10-11T00:00:01.000000Z", 3, "0.000100", sql)), HeadPolicy::byDefault);

		assertEquals(List.of(sql), c.records().stream().map(TraceRecord::sql).toList());
	}

	@Test
	void derivesStartFromEndTimeWhenExtraFieldsAreOff() throws IOException {
		Converted c = convert(new StringReader(
				entry("2026-10-11T00:00:01.500000Z", 3, "0.250000", "SELECT 1")), HeadPolicy::byDefault);

		TraceRecord r = c.records().getFirst();
		assertEquals(250_000, r.durationMicros());
		assertEquals(1_791_676_801_250_000L, r.startMicros());
		assertNull(r.error(), "부가 필드가 없으면 실패 여부를 모른다");
	}

	@Test
	void skipsAnEntryCutOffAtTheEndOfTheFile() throws IOException {
		String log = entry("2026-10-11T00:00:01.000000Z", 3, "0.000100", "SELECT 1")
				+ "# Time: 2026-10-11T00:00:02.000000Z\n# User@Host: root[root] @ localhost []  Id:     3\n";

		Converted c = convert(new StringReader(log), HeadPolicy::byDefault);
		assertEquals(1, c.records().size());
		assertEquals(1, c.summary().skipped());
	}

	@Test
	void skipsAnEntryWhoseTimestampIsCutOff() throws IOException {
		String log = entry("2026-10-11T00:00:01.000000Z", 3, "0.000100", "SELECT 1")
				+ "# Time: 2026-10-11T00:00:02.000000Z\n# User@Host: root[root] @ localhost []  Id:     3\n"
				+ "# Query_time: 0.000100  Lock_time: 0.000000 Errno: 0 Start: 2026-10-11T00:00:01.99";

		Converted c = convert(new StringReader(log), HeadPolicy::byDefault);
		assertEquals(1, c.records().size());
		assertEquals(1, c.summary().skipped());
	}

	/** log_slow_extra가 꺼진 형식의 항목 하나. # Time은 문장이 끝난 시각이다. */
	private static String entry(String endTime, long connectionId, String queryTime, String sql) {
		return "# Time: " + endTime + "\n"
				+ "# User@Host: root[root] @ localhost []  Id: " + connectionId + "\n"
				+ "# Query_time: " + queryTime + "  Lock_time: 0.000000 Rows_sent: 0  Rows_examined: 0\n"
				+ "SET timestamp=1791676801;\n"
				+ sql + ";\n";
	}

	/** 대상 데이터베이스의 문장만 남기고, 뺀 계정의 연결은 통째로 뺀다. 계정은 User@Host 줄에서 읽는다 (#83). */
	@Test
	void keepsOnlyTheTargetDatabaseAndDropsExcludedUsers() throws IOException {
		String log = entryAs("2026-10-11T00:00:01.000000Z", "app", 3, "shop", "BEGIN")
				+ entryAs("2026-10-11T00:00:01.001000Z", "app", 3, "shop", "UPDATE t SET v = 1")
				+ entryAs("2026-10-11T00:00:01.002000Z", "backup", 4, "shop", "SELECT * FROM t")
				+ entryAs("2026-10-11T00:00:01.003000Z", "app", 5, "billing", "SELECT 1")
				// 걸러 낸 데이터베이스로 USE를 바꾼 뒤의 COMMIT은 빠지지만, 트랜잭션은 추적에서 끝난다
				+ entryAs("2026-10-11T00:00:01.004000Z", "app", 3, "billing", "COMMIT")
				+ entryAs("2026-10-11T00:00:01.005000Z", "app", 3, "shop", "SELECT 2")
				+ quit("2026-10-11T00:00:01.100000Z", "app", 3)
				+ quit("2026-10-11T00:00:01.100000Z", "backup", 4)
				+ quit("2026-10-11T00:00:01.100000Z", "app", 5);
		ScopeFilter scope = new ScopeFilter(Set.of("shop"), Set.of("backup"));
		StringWriter out = new StringWriter();
		try (RecordBuffer buffer = new RecordBuffer()) {
			SlowLogConverter.convert(new StringReader(log), out, HeadPolicy::byDefault, buffer, null, null, scope);
		}
		List<TraceRecord> records = new ArrayList<>();
		try (TraceReader reader = new TraceReader(new StringReader(out.toString()))) {
			assertEquals("database=shop;exclude-user=backup", reader.header().scope());
			for (TraceRecord r; (r = reader.next()) != null;) {
				records.add(r);
			}
		}
		assertEquals(List.of("BEGIN", "UPDATE t SET v = 1", "SELECT 2"),
				records.stream().filter(r -> r.sql() != null).map(TraceRecord::sql).toList());
		assertTrue(records.stream().allMatch(r -> r.session().equals("3")));
		assertTrue(records.getLast().endsSession());
		assertNull(records.get(2).txn(), "걸러 낸 COMMIT으로 끝난 트랜잭션 뒤의 문장이다");
		assertEquals(3, scope.droppedStatements());
		assertEquals(2, scope.droppedCloses());
	}

	/** 거르지 않으면 머리말에 조건이 없다. 예전 파일과 같은 머리말이다. */
	@Test
	void writesNoScopeWhenNothingIsFiltered() throws IOException {
		StringWriter out = new StringWriter();
		SlowLogConverter.convert(new StringReader(entryAs("2026-10-11T00:00:01.000000Z", "app", 3, "shop", "SELECT 1")), out);
		assertFalse(out.toString().lines().findFirst().orElseThrow().contains("scope"));
	}

	/** 계정과 데이터베이스를 적은 항목 하나. */
	private static String entryAs(String endTime, String user, long connectionId, String database, String sql) {
		return "# Time: " + endTime + "\n"
				+ "# User@Host: " + user + "[" + user + "] @ app1 [10.0.0.5]  Id: " + connectionId + "\n"
				+ "# Query_time: 0.000100  Lock_time: 0.000000 Rows_sent: 0  Rows_examined: 0\n"
				+ "use " + database + ";\n"
				+ "SET timestamp=1791676801;\n"
				+ sql + ";\n";
	}

	private static String quit(String endTime, String user, long connectionId) {
		return "# Time: " + endTime + "\n"
				+ "# User@Host: " + user + "[" + user + "] @ app1 [10.0.0.5]  Id: " + connectionId + "\n"
				+ "# Query_time: 0.000001  Lock_time: 0.000000 Rows_sent: 0  Rows_examined: 0\n"
				+ "SET timestamp=1791676801;\n"
				+ "# administrator command: Quit;\n";
	}
}
