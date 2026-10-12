package io.dbreplay.convert;

/**
 * 한 연결에서 첫 트랜잭션 경계를 보기 전까지의 문장을 기억한다.
 *
 * <p>첫 경계가 짝 없는 COMMIT이나 ROLLBACK이면 그 앞 문장들은 녹화 전에 열린 트랜잭션의 뒷부분일 수
 * 있다. 그때 {@link HeadPolicy}에 묻는다.
 *
 * <p>문장의 순번을 하나씩 기억하지 않고 처음 순번과 개수만 든다. 경계가 한 번도 없는 연결(자동 커밋만
 * 쓰는 연결)에서는 머리가 그 연결의 문장 전부라서, 하나씩 기억하면 문장 수만큼 메모리가 든다.
 */
public final class SessionHead {

	private boolean open = true;
	private long first;
	private int statements;
	private int writes;

	/** 경계가 아닌 문장을 썼다. */
	public void statement(long index, String sql) {
		if (!open) {
			return;
		}
		if (statements++ == 0) {
			first = index;
		}
		if (SqlWords.writes(sql)) {
			writes++;
		}
	}

	/**
	 * 경계가 되는 문장을 썼다. 이 연결의 트랜잭션 상태는 여기서부터 알 수 있다.
	 *
	 * @param session   이 연결의 식별자. 뺄 때 이 연결의 처음 문장부터 이 경계까지를 가리킨다
	 * @param orphanEnd 열린 트랜잭션 없이 온 COMMIT이나 ROLLBACK이면 true
	 */
	public void boundary(String session, long index, boolean orphanEnd, String sql, HeadPolicy policy, RecordBuffer buffer) {
		if (!open) {
			return;
		}
		if (orphanEnd && statements > 0) {
			String word = SqlWords.first(sql);
			boolean rolledBack = word.equals("ROLLBACK") || word.equals("ABORT");
			if (policy.decide(new HeadPolicy.Head(statements, writes, rolledBack)) == HeadPolicy.Decision.DROP) {
				// 이 연결이 그사이에 쓴 것은 머리의 문장들과 이 경계뿐이다
				buffer.dropRange(session, first, index);
			}
		}
		open = false;
	}
}
