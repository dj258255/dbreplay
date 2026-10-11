package io.dbreplay.replay;

/** 대상 DB의 연결 하나. 한 스레드만 쓴다. */
public interface TargetSession extends AutoCloseable {

	/** 실패했지만 대상이 오류 번호를 주지 않았을 때의 값 */
	int UNKNOWN_ERROR = -1;

	/**
	 * 문장을 실행하고 결과를 끝까지 읽는다. 예외를 던지지 않는다.
	 *
	 * @param db 문장이 실행될 기본 데이터베이스. 모르면 null
	 * @return 성공이면 0, 실패면 대상의 오류 번호
	 */
	int execute(String db, String sql);

	@Override
	void close();
}
