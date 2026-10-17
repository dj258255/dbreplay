package io.dbreplay.explore;

/**
 * 수위에서 확장과 축소를 권한다. 메이투안의 시스템은 줄일 수 있는 노드와 늘릴 노드 수를 계산해 제안한다.
 *
 * <p>글에 계산식이 없어서 가정을 하나 둔다. 읽기를 받는 노드를 늘리면 읽기 상한이 노드 수에 비례해 는다.
 * 이 가정은 읽기에만 쓴다. 쓰기는 주 DB 하나가 받으므로 노드 수가 아니라 지금 사양의 몇 배가 필요한지를 낸다.
 * 가정이 맞는지는 확인하지 않았다.
 *
 * @param readNodes            지금 읽기를 받는 노드 수
 * @param recommendedReadNodes 권하는 수. 수위가 건강 구간의 위 끝을 넘지 않는 가장 작은 수이고, 최소 노드 수보다 작지 않다
 * @param writeCapacityFactor  쓰기 수위가 건강 구간의 위 끝에 오려면 주 DB의 용량이 지금의 몇 배여야 하는가
 * @param capped               계산한 수가 한도를 넘어 한도로 자른 것이면 true
 * @param readVerdict          읽기 수위의 판정
 * @param writeVerdict         쓰기 수위의 판정
 */
public record ScaleAdvice(int readNodes, int recommendedReadNodes, double writeCapacityFactor, boolean capped,
		WaterLevel.Verdict readVerdict, WaterLevel.Verdict writeVerdict) {

	/**
	 * @param minReadNodes 재해 복구 요건으로 지켜야 하는 최소 노드 수
	 * @param maxReadNodes 이보다 많이는 권하지 않는다. 상한이 0에 가깝게 나오면 계산한 수가 터무니없이 커진다
	 */
	public static ScaleAdvice of(WaterLevel read, WaterLevel write, int readNodes, int minReadNodes, int maxReadNodes) {
		checkNodes(readNodes, minReadNodes, maxReadNodes);
		if (read.level() < 0) {
			throw new IllegalArgumentException("수위가 음수다. 지금의 QPS와 상한을 확인한다");
		}
		double needed = readNodes;
		if (read.verdict() != WaterLevel.Verdict.HEALTHY) {
			// 수위는 노드 수에 반비례한다고 본다. 위 끝에 맞는 노드 수를 올림해 여유를 둔다
			needed = Math.max(minReadNodes, Math.ceil(readNodes * read.level() / read.high() - 1e-9));
		}
		// 정수로 바꾸기 전에 자른다. 수위가 무한대거나 숫자가 아니면 한도가 된다
		boolean capped = !(needed <= maxReadNodes);
		int recommended = capped ? maxReadNodes : (int) needed;
		return new ScaleAdvice(readNodes, recommended, write.capacityFactor(), capped, read.verdict(), write.verdict());
	}

	/** 노드 수는 1 이상이고 최소가 한도보다 크지 않아야 한다. 탐색을 시작하기 전에도 부른다. */
	public static void checkNodes(int readNodes, int minReadNodes, int maxReadNodes) {
		if (readNodes < 1 || minReadNodes < 1 || maxReadNodes < 1) {
			throw new IllegalArgumentException("노드 수는 1 이상이어야 한다");
		}
		if (minReadNodes > maxReadNodes) {
			throw new IllegalArgumentException("최소 노드 수가 한도보다 크다");
		}
	}

	/**
	 * 쓰기가 모자라 주 DB를 키워야 하는데 키울 수 있는 한도를 넘으면 몇 조각으로 나눠야 하는가. 1이면 나누지 않는다.
	 * 조각마다 쓰기가 고르게 나뉜다고 본다. 글은 위험한 클러스터를 분할한 사례만 들고 식은 없다(원문 대조 57, #85).
	 *
	 * @param maxWriteScale 주 DB의 용량을 지금의 몇 배까지 키울 수 있는가
	 */
	public int shards(double maxWriteScale) {
		if (!(maxWriteScale >= 1) || Double.isInfinite(maxWriteScale)) {
			throw new IllegalArgumentException("--max-write-scale에는 1 이상의 수를 준다");
		}
		if (writeVerdict != WaterLevel.Verdict.SHORT || !(writeCapacityFactor > maxWriteScale)) {
			return 1;
		}
		return (int) Math.ceil(writeCapacityFactor / maxWriteScale - 1e-9);
	}

	public int readNodeChange() {
		return recommendedReadNodes - readNodes;
	}

	/**
	 * 확장·축소 명령을 실행하면 안 되는 이유. 실행해도 되면 null이다.
	 * 권고는 가정 위의 계산이다. 하나라도 의심스러우면 실행하지 않는 쪽으로 둔다.
	 *
	 * @param limitGiven       사용자가 노드 수의 한도를 줬는가
	 * @param ceilingConfirmed 상한이 알람으로 확인된 값인가. 아니면 "그 이상"이라는 아래쪽 값일 뿐이다
	 */
	public String whyNotApplied(boolean limitGiven, boolean ceilingConfirmed) {
		if (!limitGiven) {
			return "--max-read-nodes로 한도를 주지 않았다";
		}
		if (capped) {
			return "계산한 노드 수가 한도를 넘었다. 한도에 맞춘 값으로는 실행하지 않는다";
		}
		if (!ceilingConfirmed) {
			return "상한이 알람으로 확인된 값이 아니다";
		}
		return null;
	}
}
