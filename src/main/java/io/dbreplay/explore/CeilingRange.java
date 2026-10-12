package io.dbreplay.explore;

import java.util.ArrayList;
import java.util.List;

/**
 * 같은 절차의 탐색을 여러 번 되풀이했을 때 나온 상한들을 범위로 묶는다.
 *
 * <p>실험 12에서 같은 절차로 탐색을 5번 되풀이했더니 상한이 8.5, 12, 8.5, 3.75, 8배속으로 나왔다. 숫자 하나로
 * 말할 수 없어서 최소, 중앙값, 최대, 퍼짐을 함께 낸다.
 *
 * <p>대표값은 최솟값이다. N번 중 한 번이라도 알람이 울린 배속은 버티지 못한 것으로 본다. 높은 상한을
 * 믿으면 수위가 실제보다 낮게 나와 용량이 남는다고 잘못 판정하게 된다.
 *
 * @param ceilings      탐색마다의 상한 배속. 탐색한 순서 그대로이고 바꿀 수 없는 리스트다
 * @param stoppedByAtMin 가장 낮은 상한을 낸 탐색의 {@link Explorer.Result#stoppedBy()}. 그런 탐색이 여럿이면
 *                       먼저 돈 것. null일 수 있다
 * @param allConfirmed   모든 탐색의 {@code stoppedBy()}가 {@link RoundJudge.State#OVERLOADED}이면 true.
 *                       하나라도 null이나 DISTORTED면 false다. 상한이 알람으로 확인된 값이 아니면
 *                       "그 이상"이라는 아래쪽 값일 뿐이다
 */
public record CeilingRange(List<Double> ceilings, RoundJudge.State stoppedByAtMin, boolean allConfirmed) {

	public CeilingRange {
		ceilings = List.copyOf(ceilings);
	}

	/** @throws IllegalArgumentException 탐색 결과가 하나도 없으면 상한을 낼 수 없다 */
	public static CeilingRange of(List<Explorer.Result> results) {
		if (results.isEmpty()) {
			throw new IllegalArgumentException("탐색 결과가 하나도 없다. 상한을 낼 수 없다");
		}
		List<Double> ceilings = new ArrayList<>();
		RoundJudge.State stoppedByAtMin = null;
		double lowest = Double.POSITIVE_INFINITY;
		boolean allConfirmed = true;
		for (Explorer.Result result : results) {
			ceilings.add(result.ceilingSpeed());
			// 같은 값이 여럿이면 먼저 돈 탐색의 판정을 그대로 둔다
			if (result.ceilingSpeed() < lowest) {
				lowest = result.ceilingSpeed();
				stoppedByAtMin = result.stoppedBy();
			}
			if (result.stoppedBy() != RoundJudge.State.OVERLOADED) {
				allConfirmed = false;
			}
		}
		return new CeilingRange(ceilings, stoppedByAtMin, allConfirmed);
	}

	/** 탐색 횟수 */
	public int count() {
		return ceilings.size();
	}

	/** 가장 낮은 상한 */
	public double min() {
		return sorted().get(0);
	}

	/** 중앙값. 짝수 개면 가운데 둘의 평균 */
	public double median() {
		List<Double> sorted = sorted();
		int middle = sorted.size() / 2;
		if (sorted.size() % 2 == 1) {
			return sorted.get(middle);
		}
		return (sorted.get(middle - 1) + sorted.get(middle)) / 2;
	}

	/** 가장 높은 상한 */
	public double max() {
		List<Double> sorted = sorted();
		return sorted.get(sorted.size() - 1);
	}

	/** (최대 - 최소) / 중앙값. 중앙값이 0이면 0 */
	public double spread() {
		double median = median();
		if (median == 0) {
			return 0;
		}
		return (max() - min()) / median;
	}

	/** 수위 판정과 평가 파일에 쓸 값. 최솟값이다 */
	public double representative() {
		return min();
	}

	/** 상한이 0인 탐색의 수. 시작 배속에서도 버티지 못한 탐색이다 */
	public long notHeld() {
		return ceilings.stream().filter(ceiling -> ceiling == 0).count();
	}

	/** 최솟값을 상한으로 본 수위. 상한 QPS는 {@code recordedQps * min()} */
	public WaterLevel levelAtMin(double recordedQps, double currentQps, double low, double high) {
		return new WaterLevel(currentQps, recordedQps * min(), low, high);
	}

	/** 최댓값을 상한으로 본 수위 */
	public WaterLevel levelAtMax(double recordedQps, double currentQps, double low, double high) {
		return new WaterLevel(currentQps, recordedQps * max(), low, high);
	}

	/** 위 두 수위의 판정이 다르면 true. 같은 상한 범위가 확장 여부를 가르는 경우다 */
	public boolean verdictSplits(double recordedQps, double currentQps, double low, double high) {
		WaterLevel.Verdict atMin = levelAtMin(recordedQps, currentQps, low, high).verdict();
		WaterLevel.Verdict atMax = levelAtMax(recordedQps, currentQps, low, high).verdict();
		return atMin != atMax;
	}

	/** 통계는 받은 순서가 아니라 크기 순서로 낸다. ceilings 자체는 건드리지 않는다 */
	private List<Double> sorted() {
		List<Double> sorted = new ArrayList<>(ceilings);
		sorted.sort(Double::compare);
		return sorted;
	}
}