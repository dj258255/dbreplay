package io.dbreplay.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StatementKindTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			BEGIN /* s1.1 */                              | BEGIN
			start transaction read only                   | BEGIN
			/* hint */ COMMIT                             | END
			ROLLBACK /* s2.5 */                           | END
			ROLLBACK TO SAVEPOINT sp1                     | OTHER
			COMMIT AND CHAIN                              | END_AND_CHAIN
			COMMIT AND NO CHAIN                           | END
			CREATE /* s5.3 */ TABLE t (id INT)            | IMPLICIT_COMMIT
			CREATE TEMPORARY TABLE t (id INT)             | OTHER
			TRUNCATE TABLE t                              | IMPLICIT_COMMIT
			SET /* s4.1 */ autocommit = 0                 | AUTOCOMMIT_OFF
			SET @@session.autocommit=ON                   | AUTOCOMMIT_ON
			SET NAMES utf8mb4                             | NO_TRANSACTION
			DO SLEEP(0.1)                                 | NO_TRANSACTION
			PREPARE st FROM ...                           | PREPARE
			DEALLOCATE /* s6.5 */ PREPARE st              | PREPARE
			SELECT /* COMMIT */ 1                         | OTHER
			-- note\\nUPDATE t SET a = 1                   | OTHER
			CALL transfer(5, 3, 7)                        | OTHER
			""")
	void classifiesByLeadingWords(String sql, StatementKind expected) {
		assertEquals(expected, StatementKind.of(sql.replace("\\n", "\n")));
	}
}
