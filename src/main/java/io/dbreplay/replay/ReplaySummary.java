package io.dbreplay.replay;

/**
 * 재생 한 번의 요약.
 *
 * @param statements      실행한 문장 수
 * @param sessions        연 연결 수
 * @param skipped         틀지 않고 건너뛴 문장 수. 연결을 돌려 쓰는 방식에서는 세션 설정 문장을 건너뛴다
 * @param errors          대상에서 실패한 문장 수
 * @param origErrors      원본에서 실패했던 문장 수
 * @param late            계획보다 기준 이상 늦게 나간 문장 수
 * @param lateSessionBusy 그중 같은 단위의 앞 문장이 끝나지 않아 늦은 수. 나머지는 재생기가 늦은 것이다
 * @param speed           배속
 * @param traceSpanMicros 원본에서 첫 문장 시작부터 마지막 문장 시작까지
 * @param elapsedMicros   재생에서 첫 문장의 계획 시각부터 마지막 문장이 끝날 때까지
 * @param lag             계획보다 늦게 나간 시간의 분포
 * @param duration        대상에서 걸린 시간의 분포
 * @param origDuration    원본에서 걸린 시간의 분포
 */
public record ReplaySummary(
		long statements,
		long sessions,
		long skipped,
		long errors,
		long origErrors,
		long late,
		long lateSessionBusy,
		double speed,
		long traceSpanMicros,
		long elapsedMicros,
		Histogram lag,
		Histogram duration,
		Histogram origDuration) {

	/**
	 * 재생기 자신이 늦어 계획을 못 지킨 문장의 비율. 메이투안 글이 말한 왜곡의 신호다.
	 * 글은 계획 시각을 이미 지나 즉시 실행된 문장이 많으면 재생기의 동시 처리가 모자란 것이라고 했다.
	 */
	public double replayerLateRatio() {
		return statements == 0 ? 0 : (double) (late - lateSessionBusy) / statements;
	}

	/**
	 * 같은 단위의 앞 문장이 끝나지 않아 밀린 문장의 비율. 대상의 실행이 느려도, 재생기와 대상 사이의 왕복이
	 * 원본의 클라이언트보다 느려도 여기에 잡힌다. 둘은 이 숫자만으로 구분되지 않는다.
	 */
	public double sessionBusyRatio() {
		return statements == 0 ? 0 : (double) lateSessionBusy / statements;
	}
}
