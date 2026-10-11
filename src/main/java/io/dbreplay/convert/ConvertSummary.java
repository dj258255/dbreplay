package io.dbreplay.convert;

/**
 * 변환 한 번의 요약.
 *
 * @param entries     로그에서 읽은 항목 수
 * @param statements  트래픽 파일에 쓴 문장 수
 * @param closes      트래픽 파일에 쓴 연결 종료 수
 * @param sessions    문장이나 종료가 하나라도 쓰인 연결 수
 * @param ignored     재생에 쓰지 않아 뺀 항목 수. 준비 단계, 관리 명령, 서버 안내 메시지가 여기에 든다
 * @param droppedHead 머리 정책으로 뺀 문장 수
 * @param skipped     형식이 깨져 읽지 못한 항목 수
 * @param clamped     같은 연결의 앞 문장보다 이르게 찍혀 시각을 당겨 맞춘 문장 수
 */
public record ConvertSummary(long entries, long statements, long closes, long sessions, long ignored,
		long droppedHead, long skipped, long clamped) {
}
