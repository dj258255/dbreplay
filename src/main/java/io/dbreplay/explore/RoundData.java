package io.dbreplay.explore;

import io.dbreplay.replay.ReplaySummary;

/**
 * 한 라운드에서 얻은 것.
 *
 * @param speed          그 라운드의 배속
 * @param startedMillis  재생을 시작한 시각 (에포크 밀리초)
 * @param endedMillis    재생이 끝난 시각
 * @param summary        재생 요약
 * @param activeSessions 재생하는 동안 뜬 대상의 실행 중인 연결 수. 뜨지 않았으면 빈 배열
 */
public record RoundData(double speed, long startedMillis, long endedMillis, ReplaySummary summary, int[] activeSessions) {

	public int maxActiveSessions() {
		int max = 0;
		for (int v : activeSessions) {
			max = Math.max(max, v);
		}
		return max;
	}
}
