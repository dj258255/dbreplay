package io.dbreplay.replay;

/** 재생할 대상 DB. 재생기는 원본의 연결 하나마다 {@link #open}을 한 번 부른다. */
public interface Target {

	/** @param db 그 연결의 첫 문장이 실행된 데이터베이스. 모르면 null */
	TargetSession open(String db) throws Exception;

	/**
	 * 한 연결이 문장마다 데이터베이스를 바꿀 수 있는가. 그렇다면 풀은 데이터베이스와 상관없이 연결을 돌려 쓴다.
	 * 아니면(PostgreSQL) 데이터베이스마다 따로 모은다(#141).
	 */
	default boolean switchesDatabase() {
		return false;
	}
}
