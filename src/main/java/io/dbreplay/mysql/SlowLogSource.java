package io.dbreplay.mysql;

import java.io.IOException;

/**
 * 슬로우 로그 항목을 차례로 내는 곳. 서버 로그를 읽는 {@link SlowLogParser}와 패킷 캡처를 해석하는 {@link MysqlCapture}가 있다.
 * 둘이 같은 모양의 항목을 내서, 트랜잭션 경계와 머리 정책과 가림을 같은 변환기가 처리한다(#79).
 */
public interface SlowLogSource {

	/** 다음 항목. 끝이면 null. */
	SlowLogEntry next() throws IOException;

	/** 읽지 못하고 버린 항목 수. */
	long skipped();
}
