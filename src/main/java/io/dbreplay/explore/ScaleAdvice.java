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

	public int readNodeChange() {
		return recommendedReadNodes - readNodes;
	}
}
