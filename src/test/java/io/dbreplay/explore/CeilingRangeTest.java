package io.dbreplay.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CeilingRangeTest {

	/** 상한과 그 상한을 만든 판정으로 탐색 결과 하나를 만든다. 라운드는 이 테스트에서 쓰지 않는다. */
	private static Explorer.Result result(double ceilingSpeed, RoundJudge.State stoppedBy) {
		return new Explorer.Result(List.of(), ceilingSpeed, stoppedBy);
	}

	private static Explorer.Result overloaded(double ceilingSpeed) {
		return result(ceilingSpeed, RoundJudge.State.OVERLOADED);
	}

	/**
	 * 실험 12의 다섯 번 탐색. 상한이 8.5, 12, 8.5, 3.75, 8배속으로 흩어졌다.
	 * 범위의 모든 통계와 대표값, 순서 보존을 한 번에 본다.
	 */
	@Test
	void summarizesTheFiveRunsOfExperimentTwelve() {
		CeilingRange range = CeilingRange.of(List.of(overloaded(8.5), overloaded(12), overloaded(8.5),
				overloaded(3.75), overloaded(8)));

		assertEquals(5, range.count());
		assertEquals(3.75, range.min(), 1e-9);
		assertEquals(8.5, range.median(), 1e-9);
		assertEquals(12, range.max(), 1e-9);
		assertEquals((12 - 3.75) / 8.5, range.spread(), 1e-9);
		assertEquals(3.75, range.representative(), 1e-9);
		assertEquals(List.of(8.5, 12.0, 8.5, 3.75, 8.0), range.ceilings(), "받은 순서 그대로 둔다");
		assertTrue(range.allConfirmed());
		assertEquals(0, range.notHeld());
	}

	/** 짝수 개면 가운데 둘의 평균이 중앙값이다. */
	@Test
	void takesTheAverageOfTheTwoMiddleValuesForAnEvenCount() {
		CeilingRange range = CeilingRange.of(List.of(overloaded(10), overloaded(14), overloaded(11), overloaded(14)));

		assertEquals(12.5, range.median(), 1e-9);
	}

	/** 탐색이 한 번뿐이면 최소, 중앙값, 최대가 모두 같고 퍼짐은 없다. */
	@Test
	void aSingleRunHasNoSpread() {
		CeilingRange range = CeilingRange.of(List.of(overloaded(9.0)));

		assertEquals(9.0, range.min(), 1e-9);
		assertEquals(9.0, range.median(), 1e-9);
		assertEquals(9.0, range.max(), 1e-9);
		assertEquals(0, range.spread(), 1e-9);
	}

	/** 대표값은 상한이 가장 낮은 탐색의 판정을 따른다. 같은 값이 여럿이면 먼저 돈 것의 판정이다. */
	@Test
	void stoppedByAtMinFollowsTheLowestCeilingRunAndKeepsTheFirstOnTies() {
		CeilingRange tieKeepsFirst = CeilingRange.of(List.of(overloaded(3.75), result(3.75, RoundJudge.State.DISTORTED),
				overloaded(12)));

		assertEquals(RoundJudge.State.OVERLOADED, tieKeepsFirst.stoppedByAtMin(), "같은 값이면 먼저 돈 탐색의 판정을 따른다");

		CeilingRange distortedAtMin = CeilingRange.of(List.of(overloaded(12), overloaded(8),
				result(3.75, RoundJudge.State.DISTORTED)));

		assertEquals(RoundJudge.State.DISTORTED, distortedAtMin.stoppedByAtMin());

		CeilingRange unconfirmedAtMin = CeilingRange.of(List.of(overloaded(12), overloaded(8), result(3.75, null)));

		assertNull(unconfirmedAtMin.stoppedByAtMin());
	}

	/** 상한이 알람으로 확인되지 않은 탐색이 하나라도 있으면 전체를 확인된 것으로 보지 않는다. */
	@Test
	void allConfirmedIsFalseWhenAnyRunWasNotStoppedByAnAlarm() {
		CeilingRange withNull = CeilingRange.of(List.of(overloaded(8.5), result(9, null)));
		assertFalse(withNull.allConfirmed());

		CeilingRange withDistorted = CeilingRange.of(List.of(overloaded(8.5), result(9, RoundJudge.State.DISTORTED)));
		assertFalse(withDistorted.allConfirmed());
	}

	/** 시작 배속에서도 못 버틴 탐색(상한 0)을 세고, 그것이 대표값이 된다. 전부 0이면 0으로 나누지 않는다. */
	@Test
	void countsRunsThatDidNotHoldEvenTheStartSpeed() {
		CeilingRange mixed = CeilingRange.of(List.of(overloaded(0), overloaded(8), overloaded(0), overloaded(12)));

		assertEquals(2, mixed.notHeld());
		assertEquals(0, mixed.min(), 1e-9);

		CeilingRange allZero = CeilingRange.of(List.of(overloaded(0), overloaded(0)));

		assertEquals(2, allZero.notHeld());
		assertEquals(0, allZero.spread(), 1e-9, "중앙값이 0이면 퍼짐도 0이다");
	}

	/** 상한 범위가 확장 여부를 가르는 경우를 본다. 최솟값으로는 모자라지만 최댓값으로는 건강한 구간이다. */
	@Test
	void verdictSplitsWhenTheLowestAndHighestCeilingsDisagree() {
		CeilingRange range = CeilingRange.of(List.of(overloaded(3.75), overloaded(12)));

		assertEquals(WaterLevel.Verdict.SHORT, range.levelAtMin(3980, 15920, 0.3, 0.5).verdict());
		assertEquals(WaterLevel.Verdict.HEALTHY, range.levelAtMax(3980, 15920, 0.3, 0.5).verdict());
		assertTrue(range.verdictSplits(3980, 15920, 0.3, 0.5));

		assertEquals(WaterLevel.Verdict.SURPLUS, range.levelAtMin(3980, 3980, 0.3, 0.5).verdict());
		assertEquals(WaterLevel.Verdict.SURPLUS, range.levelAtMax(3980, 3980, 0.3, 0.5).verdict());
		assertFalse(range.verdictSplits(3980, 3980, 0.3, 0.5));
	}

	/** 상한을 낼 탐색이 없으면 범위도 없다. */
	@Test
	void refusesAnEmptyResultList() {
		assertThrows(IllegalArgumentException.class, () -> CeilingRange.of(List.of()));
	}

	/** ceilings는 바깥에서 바꿀 수 없어야 한다. 범위를 낸 뒤에 값이 달라지면 안 된다. */
	@Test
	void ceilingsCannotBeChangedFromOutside() {
		CeilingRange range = CeilingRange.of(List.of(overloaded(8.5), overloaded(12)));

		assertThrows(UnsupportedOperationException.class, () -> range.ceilings().add(3.0));
	}
}