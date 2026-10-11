package io.dbreplay.convert;

import java.util.ArrayList;
import java.util.List;

/**
 * 한 연결에서 첫 트랜잭션 경계를 보기 전까지의 문장을 기억한다.
 *
 * <p>첫 경계가 짝 없는 COMMIT이나 ROLLBACK이면 그 앞 문장들은 녹화 전에 열린 트랜잭션의 뒷부분일 수
 * 있다. 그때 {@link HeadPolicy}에 묻는다.
 */
public final class SessionHead {

	private List<Integer> indexes = new ArrayList<>();
	private int writes;

	/** 경계가 아닌 문장을 썼다. */
	public void statement(int index, String sql) {
		if (indexes == null) {
			return;
		}
		indexes.add(index);
		if (SqlWords.writes(sql)) {
			writes++;
		}
	}

	/**
	 * 경계가 되는 문장을 썼다. 이 연결의 트랜잭션 상태는 여기서부터 알 수 있다.
	 *
	 * @param orphanEnd 열린 트랜잭션 없이 온 COMMIT이나 ROLLBACK이면 true
	 */
	public void boundary(int index, boolean orphanEnd, String sql, HeadPolicy policy, RecordBuffer buffer) {
		if (indexes == null) {
			return;
		}
		if (orphanEnd && !indexes.isEmpty()) {
			String first = SqlWords.first(sql);
			boolean rolledBack = first.equals("ROLLBACK") || first.equals("ABORT");
			if (policy.decide(new HeadPolicy.Head(indexes.size(), writes, rolledBack)) == HeadPolicy.Decision.DROP) {
				indexes.forEach(buffer::drop);
				buffer.drop(index);
			}
		}
		indexes = null;
	}
}
