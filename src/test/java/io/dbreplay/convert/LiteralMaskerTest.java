package io.dbreplay.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.dbreplay.convert.LiteralMasker.Level;
import io.dbreplay.mysql.SlowLogConverter;
import io.dbreplay.postgres.CsvLogConverter;
import io.dbreplay.report.SqlShape;
import io.dbreplay.trace.TraceHeader;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;

class LiteralMaskerTest {

	private static final byte[] KEY = "test-key".getBytes(StandardCharsets.UTF_8);

	private static LiteralMasker mysql(Level level) {
		return new LiteralMasker(level, true, KEY);
	}

	private static LiteralMasker postgres(Level level) {
		return new LiteralMasker(level, false, KEY);
	}

	@Test
	void hidesTextButKeepsLengthAndTheKindOfEachCharacter() {
		String sql = "INSERT INTO users (id, name, email, phone) VALUES (4821, '김범수', 'Beomsu.Kim@example.com', '010-1234-5678')";
		String masked = mysql(Level.STRINGS).mask(sql);

		assertEquals(sql.length(), masked.length());
		assertFalse(masked.contains("김범수") || masked.contains("Beomsu") || masked.contains("example") || masked.contains("1234"), masked);
		assertTrue(masked.matches("INSERT INTO users \\(id, name, email, phone\\) VALUES \\(4821, '[가-힣]{3}', "
				+ "'[A-Z][a-z]{5}\\.[A-Z][a-z]{2}@[a-z]{7}\\.[a-z]{3}', '\\d{3}-\\d{4}-\\d{4}'\\)"), masked);
		assertEquals(SqlShape.of(sql), SqlShape.of(masked), "가려도 SQL 모양은 같아야 비교가 된다");
	}

	/** 넣은 값을 뒤에서 다시 찾는 문장이 가린 뒤에도 같은 행을 찾아야 한다. */
	@Test
	void theSameValueAlwaysBecomesTheSameValue() {
		LiteralMasker masker = mysql(Level.STRINGS);
		String inserted = masker.mask("INSERT INTO t (email) VALUES ('a@b.com')");
		String selected = masker.mask("SELECT * FROM t WHERE email = 'a@b.com'");
		String value = inserted.substring(inserted.indexOf('\''), inserted.lastIndexOf('\'') + 1);

		assertTrue(selected.endsWith(value), selected);
		assertNotEquals(masker.mask("SELECT 'a@b.com'"), new LiteralMasker(Level.STRINGS, true, "other".getBytes(StandardCharsets.UTF_8)).mask("SELECT 'a@b.com'"),
				"키가 다르면 결과가 다르다");
	}

	@Test
	void stringsLevelLeavesShortNumbersAsIdentifiersAndHidesLongOnes() {
		String masked = mysql(Level.STRINGS).mask("UPDATE sbtest1 SET k=k+1, card=1234567890123456 WHERE id=50234 AND rrn='9001011234567'");

		assertTrue(masked.matches("UPDATE sbtest1 SET k=k\\+1, card=\\d{16} WHERE id=50234 AND rrn='\\d{13}'"), masked);
		assertFalse(masked.contains("1234567890123456") || masked.contains("9001011234567"), masked);
	}

	/** PostgreSQL의 로그는 인자를 전부 따옴표로 감싼다. 따옴표로 나누면 기본키가 문자열이 된다. */
	@Test
	void aQuotedNumberIsANumber() {
		String sql = "UPDATE pgbench_accounts SET abalance = abalance + '-2582' WHERE aid = '152661'";

		assertEquals(sql, postgres(Level.STRINGS).mask(sql));
		String all = postgres(Level.ALL).mask(sql);
		assertTrue(all.matches("UPDATE pgbench_accounts SET abalance = abalance \\+ '-[1-9]\\d{3}' WHERE aid = '[1-9]\\d{5}'"), all);
		assertNotEquals(sql, all);
	}

	@Test
	void allLevelHidesNumbersButNotIdentifiersLimitsOrDates() {
		String sql = "SELECT c1, t2.col3 FROM sbtest1 t2 WHERE id BETWEEN 49955 AND 50054 AND d > '2026-10-12 09:30:00' AND x = 0.25 LIMIT 10 OFFSET 20";
		String masked = mysql(Level.ALL).mask(sql);

		assertTrue(masked.matches("SELECT c1, t2\\.col3 FROM sbtest1 t2 WHERE id BETWEEN [1-9]\\d{4} AND [1-9]\\d{4} "
				+ "AND d > '2026-10-12 09:30:00' AND x = 0\\.\\d{2} LIMIT 10 OFFSET 20"), masked);
		assertFalse(masked.contains("49955") || masked.contains("50054"), masked);
	}

	@Test
	void leavesCommentsQuotedIdentifiersAndEscapesAlone() {
		String masked = mysql(Level.ALL).mask("SELECT `col1`, 'it''s', 'a\\'b' /* user 4821 'x' */ FROM t -- tail 'y' 99");
		assertTrue(masked.matches("SELECT `col1`, '[a-z]{2}''[a-z]', '[a-z]\\\\'[a-z]' /\\* user 4821 'x' \\*/ FROM t -- tail 'y' 99"), masked);

		assertEquals("SELECT a--1", mysql(Level.STRINGS).mask("SELECT a--1"), "MySQL에서 공백 없는 --는 뺄셈이다");
		assertTrue(mysql(Level.ALL).mask("SELECT a--1234").matches("SELECT a--[1-9]\\d{3}"));

		// PostgreSQL에서 큰따옴표 안은 식별자다. MySQL에서는 문자열이다
		assertEquals("SELECT \"Name\" FROM t", postgres(Level.ALL).mask("SELECT \"Name\" FROM t"));
		assertNotEquals("SELECT \"Name\" FROM t", mysql(Level.ALL).mask("SELECT \"Name\" FROM t"));
		// PostgreSQL에서 역슬래시는 보통 글자다. 문자열이 역슬래시로 끝나도 거기서 닫힌다
		assertTrue(postgres(Level.STRINGS).mask("SELECT 'C:\\', 'abc'").matches("SELECT '[A-Z]:\\\\', '[a-z]{3}'"));
	}

	/** 실제 서버의 로그를 가려서 변환해도 문장 수, 순서, SQL 모양, 트랜잭션 순번이 그대로여야 재생과 비교가 된다. */
	@Test
	void maskingARealLogKeepsEveryShapeAndTransaction() throws IOException {
		for (boolean mysql : new boolean[] { true, false }) {
			List<TraceRecord> plain = new ArrayList<>();
			List<TraceRecord> masked = new ArrayList<>();
			assertEquals(null, convert(mysql, null, plain).mask());
			assertEquals("all", convert(mysql, new LiteralMasker(Level.ALL, mysql, KEY), masked).mask());

			assertEquals(plain.size(), masked.size());
			int changed = 0;
			for (int i = 0; i < plain.size(); i++) {
				TraceRecord a = plain.get(i);
				TraceRecord b = masked.get(i);
				assertEquals(a.session(), b.session());
				assertEquals(a.txn(), b.txn());
				if (a.sql() != null) {
					assertEquals(SqlShape.of(a.sql()), SqlShape.of(b.sql()));
					assertEquals(a.sql().length(), b.sql().length());
					changed += a.sql().equals(b.sql()) ? 0 : 1;
				}
			}
			assertTrue(changed > plain.size() / 4, "값이 든 문장은 바뀌어야 한다: " + changed + "/" + plain.size());
		}
	}

	private static TraceHeader convert(boolean mysql, LiteralMasker masker, List<TraceRecord> into) throws IOException {
		StringWriter out = new StringWriter();
		String name = masker == null ? null : "all";
		try (RecordBuffer buffer = new RecordBuffer();
				Reader log = new InputStreamReader(LiteralMaskerTest.class.getResourceAsStream(
						mysql ? "/mysql/exp01-slow.log" : "/postgres/exp04-pgbench.csv"), StandardCharsets.UTF_8)) {
			if (mysql) {
				SlowLogConverter.convert(log, out, HeadPolicy::byDefault, buffer, masker, name);
			}
			else {
				CsvLogConverter.convert(log, out, HeadPolicy::byDefault, buffer, masker, name);
			}
		}
		try (TraceReader reader = new TraceReader(new StringReader(out.toString()))) {
			for (TraceRecord r; (r = reader.next()) != null;) {
				into.add(r);
			}
			return reader.header();
		}
	}

	@Test
	void anUnclosedQuoteDoesNotThrow() {
		String masked = mysql(Level.STRINGS).mask("SELECT 'abc");
		assertEquals(11, masked.length());
		assertTrue(masked.startsWith("SELECT '"), masked);
	}
}
