package io.dbreplay.replay;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 재생한 문장 하나의 결과.
 *
 * @param seq                트래픽 파일에서의 줄 순번 (머리말 다음 줄이 0). 문장 본문은 이 번호로 트래픽 파일에서 찾는다
 * @param session            원본의 연결 식별자
 * @param planMicros         재생 시작부터 이 문장이 나가기로 한 시각까지
 * @param lagMicros          계획보다 늦게 나간 시간
 * @param sessionBusy        계획 시각에 같은 연결의 앞 문장이 아직 끝나지 않았으면 true
 * @param durationMicros     대상에서 걸린 시간
 * @param error              대상의 오류 번호. 0은 성공
 * @param origDurationMicros 원본에서 걸린 시간
 * @param origError          원본의 오류 번호. 녹화가 알려 주지 않았으면 없다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplayResult(
		long seq,
		String session,
		long planMicros,
		long lagMicros,
		boolean sessionBusy,
		long durationMicros,
		int error,
		long origDurationMicros,
		Integer origError) {
}
