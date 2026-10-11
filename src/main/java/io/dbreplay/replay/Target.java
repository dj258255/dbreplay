package io.dbreplay.replay;

/** 재생할 대상 DB. 재생기는 원본의 연결 하나마다 {@link #open}을 한 번 부른다. */
public interface Target {

	/** @param db 그 연결의 첫 문장이 실행된 데이터베이스. 모르면 null */
	TargetSession open(String db) throws Exception;
}
