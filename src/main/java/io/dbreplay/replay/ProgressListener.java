package io.dbreplay.replay;

/**
 * 재생하는 동안 1초마다 재생기의 상태를 받는다. 원문 그림 7의 "매초 통계"다. 그 에이전트는 매초 자기 CPU 사용률, 고루틴 수,
 * 보낸/처리한 SQL, 막힌 SQL, 오류 SQL을 센다(#127). 재생이 끝나면 마지막 상태를 한 번 더 받는다.
 *
 * <p>여러 재생기가 함께 틀면(--workers) 여러 스레드에서 불릴 수 있다.
 */
@FunctionalInterface
public interface ProgressListener {

	/**
	 * @param part        나눠 튼 조각의 번호. 하나로 틀면 0
	 * @param seconds     재생을 시작한 뒤 지난 초
	 * @param dispatched  실행 스레드에 넘긴 문장 수. 계획 시각보다 1초 앞서 넘긴다
	 * @param sent        대상에 보낸 문장 수
	 * @param done        끝난 문장 수
	 * @param late        재생기가 늦게 보낸 문장 수(왜곡의 신호)
	 * @param errors      대상에서 실패한 문장 수
	 * @param connections 지금까지 연 연결 수
	 * @param cpuPercent  지난 1초 동안 이 프로세스의 CPU 사용률. 코어 전체에 대한 백분율이다. 모르면 음수
	 * @param last        재생이 끝난 뒤의 마지막 상태인가
	 */
	record Progress(int part, double seconds, long dispatched, long sent, long done, long late, long errors, long connections,
			double cpuPercent, boolean last) {

		/** 보냈지만 아직 끝나지 않은 문장. 대상이 느려지면 는다. */
		public long running() {
			return sent - done;
		}

		/** 넘겼지만 아직 보내지 않은 문장. 계획 시각을 기다리거나 연결을 기다리는 것이다. */
		public long waiting() {
			return dispatched - sent;
		}
	}

	void accept(Progress progress);
}
