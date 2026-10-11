package io.dbreplay.convert;

/**
 * 녹화 구간의 머리에서 잘린 트랜잭션을 어떻게 할지 정한다.
 *
 * <p>어떤 연결에서 처음 본 경계가 짝 없는 COMMIT이나 ROLLBACK이면, 그 앞의 문장들은 녹화가 시작되기
 * 전에 열린 트랜잭션의 뒷부분일 수 있다. 앞부분은 로그에 없고, 녹화 시작 시점의 스냅샷에도 없다.
 */
@FunctionalInterface
public interface HeadPolicy {

	enum Decision {
		/** 문장들을 그대로 둔다. 재생에서는 하나씩 자동 커밋된다 */
		KEEP,
		/** 문장들과 짝 없는 COMMIT·ROLLBACK을 트래픽 파일에서 뺀다 */
		DROP
	}

	/**
	 * @param statements 짝 없는 COMMIT·ROLLBACK 앞에 있던 문장 수
	 * @param writes     그중 데이터를 바꿀 수 있는 문장 수
	 * @param rolledBack 원본에서 ROLLBACK으로 끝났으면 true
	 */
	record Head(int statements, int writes, boolean rolledBack) {
	}

	Decision decide(Head head);

	/** 따로 지정하지 않았을 때 쓰는 정책. */
	static Decision byDefault(Head head) {
		// TODO(범수): 머리에서 잘린 트랜잭션의 기본 처리를 정한다. 지금은 전부 남긴다.
		return Decision.KEEP;
	}
}
