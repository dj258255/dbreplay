package io.dbreplay.mysql;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.dbreplay.convert.SqlWords;

/** 트랜잭션 경계를 따라가는 데 필요한 만큼만 나눈 MySQL 문장 종류. SQL을 파싱하지 않고 앞 단어로 가른다. */
public enum StatementKind {

	/** BEGIN, START TRANSACTION */
	BEGIN,
	/** COMMIT, ROLLBACK. 세이브포인트로 되돌리는 ROLLBACK TO는 여기에 들지 않는다 */
	END,
	/** COMMIT AND CHAIN처럼 끝내면서 곧바로 새 트랜잭션을 여는 문장 */
	END_AND_CHAIN,
	/** 실행되면 열려 있던 트랜잭션을 암묵적으로 커밋하는 문장 (DDL 등) */
	IMPLICIT_COMMIT,
	AUTOCOMMIT_ON,
	AUTOCOMMIT_OFF,
	/** SQL 문법의 PREPARE와 DEALLOCATE. 실행 항목에 본문이 다 들어 있어 재생에 필요 없다 */
	PREPARE,
	/** autocommit=0에서도 트랜잭션을 열지 않는 문장 (SET, DO, SHOW, USE) */
	NO_TRANSACTION,
	OTHER;

	// MySQL 8.4 참조 설명서 "Statements That Cause an Implicit Commit"의 첫 단어들
	private static final Set<String> IMPLICIT_COMMIT_HEADS = Set.of(
			"ALTER", "CREATE", "DROP", "RENAME", "TRUNCATE", "INSTALL", "UNINSTALL",
			"GRANT", "REVOKE", "LOCK", "UNLOCK", "ANALYZE", "CHECK", "OPTIMIZE", "REPAIR",
			"CACHE", "FLUSH", "RESET");
	private static final Set<String> NO_TRANSACTION_HEADS = Set.of("SET", "DO", "SHOW", "USE");

	private static final Pattern AUTOCOMMIT = Pattern.compile("\\bautocommit\\s*:?=\\s*(0|1|on|off|true|false)\\b", Pattern.CASE_INSENSITIVE);

	public static StatementKind of(String sql) {
		String[] words = SqlWords.head(sql);
		String first = words[0];
		String second = words.length > 1 ? words[1] : "";
		String third = words.length > 2 ? words[2] : "";

		switch (first) {
			case "BEGIN":
				return BEGIN;
			case "START":
				return second.equals("TRANSACTION") ? BEGIN : IMPLICIT_COMMIT; // START REPLICA 등
			case "COMMIT":
				return SqlWords.chained(words) ? END_AND_CHAIN : END;
			case "ROLLBACK":
				if (second.equals("TO") || (second.equals("WORK") && third.equals("TO"))) {
					return OTHER;
				}
				return SqlWords.chained(words) ? END_AND_CHAIN : END;
			case "PREPARE", "DEALLOCATE":
				return PREPARE;
			case "SET":
				Matcher m = AUTOCOMMIT.matcher(sql);
				if (m.find()) {
					String v = m.group(1).toLowerCase(Locale.ROOT);
					return v.equals("1") || v.equals("on") || v.equals("true") ? AUTOCOMMIT_ON : AUTOCOMMIT_OFF;
				}
				return second.equals("PASSWORD") ? IMPLICIT_COMMIT : NO_TRANSACTION;
			case "CREATE", "DROP":
				// 임시 테이블의 생성과 삭제는 트랜잭션을 건드리지 않는다
				return second.equals("TEMPORARY") ? OTHER : IMPLICIT_COMMIT;
			default:
				if (IMPLICIT_COMMIT_HEADS.contains(first)) {
					return IMPLICIT_COMMIT;
				}
				return NO_TRANSACTION_HEADS.contains(first) ? NO_TRANSACTION : OTHER;
		}
	}

}
