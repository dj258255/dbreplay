package io.dbreplay.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SqlShapeTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
			SELECT c FROM sbtest1 WHERE id BETWEEN 49955 AND 50054          | SELECT c FROM sbtest1 WHERE id BETWEEN ? AND ?
			UPDATE /* s1.2 */ t SET c='it''s', k = k+1 WHERE id IN (1, 2,3) | UPDATE t SET c=?, k = k+? WHERE id IN (?+)
			INSERT INTO t (a, b) VALUES (1, 'x'), (2, 'y'), (3, 'z')        | INSERT INTO t (a, b) VALUES (?+), ...
			INSERT INTO t (a, b) VALUES (1, 'x')                            | INSERT INTO t (a, b) VALUES (?+)
			SELECT  *   FROM t WHERE x = -1.5e3 AND y = 0xFF -- tail        | SELECT * FROM t WHERE x = ? AND y = ?
			UPDATE pgbench_accounts SET abalance = abalance + '-4096'       | UPDATE pgbench_accounts SET abalance = abalance + ?
			SELECT "col1" FROM t2 WHERE a = $1                              | SELECT "col1" FROM t2 WHERE a = $1
			""")
	void replacesLiteralsAndKeepsIdentifiers(String sql, String expected) {
		assertEquals(expected, SqlShape.of(sql));
	}
}
