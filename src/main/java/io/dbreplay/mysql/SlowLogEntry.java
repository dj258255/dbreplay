package io.dbreplay.mysql;

/**
 * 슬로우 로그의 한 항목. 로그에 적힌 그대로이고 해석은 하지 않는다.
 *
 * @param connectionId   {@code Id:}에 찍힌 연결 번호
 * @param startMicros    문장이 시작된 시각 (UTC 에포크 마이크로초)
 * @param durationMicros {@code Query_time}
 * @param lockMicros     {@code Lock_time}
 * @param errno          오류 번호. {@code log_slow_extra}가 꺼져 있으면 null
 * @param useDb          이 항목 앞에 {@code use db;} 줄이 있었으면 그 이름, 없었으면 null
 * @param adminCommand   {@code # administrator command: X;} 항목이면 X, 아니면 null
 * @param sql            문장 본문. 끝의 세미콜론은 뗀다. 관리 명령 항목이면 null
 */
public record SlowLogEntry(
		long connectionId,
		long startMicros,
		long durationMicros,
		long lockMicros,
		Integer errno,
		String useDb,
		String adminCommand,
		String sql) {

	public boolean failed() {
		return errno != null && errno != 0;
	}
}
