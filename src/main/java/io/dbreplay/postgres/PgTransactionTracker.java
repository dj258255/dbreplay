package io.dbreplay.postgres;

import io.dbreplay.convert.SqlWords;

/**
 * 한 연결의 문장을 순서대로 받아 각 문장이 어느 트랜잭션에 속하는지 따라간다.
 *
 * <p>MySQL과 다른 점이 둘 있다. DDL이 트랜잭션을 끝내지 않고, 서버 쪽에 autocommit을 끄는 설정이 없다.
 * 그래서 경계는 BEGIN과 COMMIT·ROLLBACK뿐이다.
 */
final class PgTransactionTracker {

	enum Kind {
		BEGIN, END, END_AND_CHAIN, OTHER
	}

	private long sequence;
	private Long open;

	static Kind kindOf(String sql) {
		String[] words = SqlWords.head(sql);
		String second = words.length > 1 ? words[1] : "";
		return switch (words[0]) {
			case "BEGIN" -> Kind.BEGIN;
			case "START" -> second.equals("TRANSACTION") ? Kind.BEGIN : Kind.OTHER;
			case "COMMIT", "END", "ROLLBACK", "ABORT" -> {
				// ROLLBACK TO는 세이브포인트로 되돌리고, COMMIT PREPARED는 다른 연결이 준비한 것을 끝낸다
				if (second.equals("TO") || second.equals("PREPARED")) {
					yield Kind.OTHER;
				}
				yield SqlWords.chained(words) ? Kind.END_AND_CHAIN : Kind.END;
			}
			default -> Kind.OTHER;
		};
	}

	boolean inTransaction() {
		return open != null;
	}

	/** 이 문장이 속한 트랜잭션의 순번. 어디에도 속하지 않으면 null. */
	Long assign(Kind kind) {
		switch (kind) {
			case BEGIN -> {
				if (open == null) {
					open = ++sequence; // 이미 열려 있으면 서버는 경고만 하고 그대로 둔다
				}
				return open;
			}
			case END -> {
				Long ended = open;
				open = null;
				return ended;
			}
			case END_AND_CHAIN -> {
				Long ended = open;
				open = ended == null ? null : ++sequence;
				return ended;
			}
			default -> {
				return open;
			}
		}
	}
}
