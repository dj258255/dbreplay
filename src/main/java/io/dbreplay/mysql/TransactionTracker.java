package io.dbreplay.mysql;

/**
 * 한 연결의 문장을 순서대로 받아 각 문장이 어느 트랜잭션에 속하는지 따라간다.
 *
 * <p>규칙은 실험 01에서 performance_schema의 기록과 대조해 맞춘 것이다
 * (experiments/01-log-feasibility). 연결이 처음 보일 때는 autocommit이 켜져 있고 열린 트랜잭션이
 * 없다고 가정한다. 녹화 전부터 있던 연결이면 이 가정이 틀릴 수 있다.
 */
final class TransactionTracker {

	private boolean autocommit = true;
	private long sequence;
	private Long open;

	boolean inTransaction() {
		return open != null;
	}

	/** 이 문장이 속한 트랜잭션의 순번. 어디에도 속하지 않으면 null. */
	Long assign(StatementKind kind, boolean failed) {
		switch (kind) {
			case BEGIN -> {
				open = ++sequence; // 열려 있던 것이 있으면 서버가 먼저 커밋한다
				return open;
			}
			case END, IMPLICIT_COMMIT -> {
				Long ended = open;
				open = null;
				return ended;
			}
			case END_AND_CHAIN -> {
				Long ended = open;
				open = ended == null ? null : ++sequence;
				return ended;
			}
			case AUTOCOMMIT_ON -> {
				autocommit = true;
				open = null; // 켜는 순간 열려 있던 트랜잭션이 커밋된다
				return null;
			}
			case AUTOCOMMIT_OFF -> {
				autocommit = false;
				return open;
			}
			case NO_TRANSACTION, PREPARE -> {
				return open;
			}
			default -> {
				if (open == null && !autocommit && !failed) {
					open = ++sequence; // autocommit=0에서는 첫 문장이 트랜잭션을 연다. 조회도 연다
				}
				return open;
			}
		}
	}

	/** 연결이 끊겼다. 열려 있던 트랜잭션은 서버가 롤백한다. */
	void close() {
		open = null;
	}
}
