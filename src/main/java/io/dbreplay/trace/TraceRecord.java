package io.dbreplay.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 트래픽 파일의 한 줄. 기종과 녹화 방식에 무관하게, 재생에 필요한 최소한만 담는다.
 *
 * <p>값이 없는 항목은 파일에 쓰지 않는다. "없다"와 "0이다"는 뜻이 다르다. 예를 들어 {@code error}가
 * 없으면 녹화 쪽이 실패 여부를 알려 주지 않은 것이고, 0이면 성공한 것이다.
 *
 * @param session        원본의 연결 식별자. 같은 값의 문장은 재생 때 한 연결에서 순서대로 나간다
 * @param startMicros    원본에서 문장이 시작된 시각 (UTC 에포크 마이크로초)
 * @param durationMicros 원본에서 걸린 시간
 * @param sql            문장 본문. 연결 종료 줄이면 없다
 * @param db             그 시점의 기본 데이터베이스
 * @param error          원본의 오류 번호. 0은 성공
 * @param lockMicros     원본에서 락을 기다린 시간. 기종이 내주지 않으면 없다
 * @param txn            세션 안에서의 트랜잭션 순번. 트랜잭션에 속하지 않은 문장이면 없다
 * @param rowsSent       원본에서 클라이언트에 보낸 행 수. 녹화가 알려 주지 않으면 없다
 * @param rowsExamined   원본에서 읽은 행 수. 같은 문장인데 이 값이 크게 달라지면 실행 계획이 바뀐 것이다
 * @param close          연결 종료 줄이면 true
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"session", "startMicros", "durationMicros", "sql", "db", "error", "lockMicros", "txn", "rowsSent",
		"rowsExamined", "close"})
public record TraceRecord(
		String session,
		long startMicros,
		long durationMicros,
		String sql,
		String db,
		Integer error,
		Long lockMicros,
		Long txn,
		Long rowsSent,
		Long rowsExamined,
		Boolean close) {

	/** 행 수를 모르는 녹화에서 온 레코드. */
	public TraceRecord(String session, long startMicros, long durationMicros, String sql, String db, Integer error,
			Long lockMicros, Long txn, Boolean close) {
		this(session, startMicros, durationMicros, sql, db, error, lockMicros, txn, null, null, close);
	}

	public static TraceRecord close(String session, long startMicros) {
		return new TraceRecord(session, startMicros, 0, null, null, null, null, null, true);
	}

	public boolean endsSession() {
		return Boolean.TRUE.equals(close);
	}
}
