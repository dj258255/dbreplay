package io.dbreplay.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ZonePlanTest {

	private static Map<String, Integer> zones(Object... kv) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], (Integer) kv[i + 1]);
		}
		return m;
	}

	/** 늘릴 때는 가장 적은 존부터 채운다. 같으면 적은 순서대로다. */
	@Test
	void growsTheSmallestZoneFirst() {
		ZonePlan plan = ZonePlan.of(zones("a", 2, "b", 1, "c", 2), 8, 1, 0.8);
		assertEquals(zones("a", 3, "b", 3, "c", 2), plan.after());
		assertEquals("a=3,b=3,c=2", plan.afterText());
	}

	/** 줄일 때는 가장 많은 존부터 빼고 존마다의 최소를 지킨다. */
	@Test
	void shrinksTheLargestZoneFirstAndKeepsTheMinimum() {
		ZonePlan plan = ZonePlan.of(zones("a", 3, "b", 1, "c", 2), 3, 1, 0.1);
		assertEquals(zones("a", 1, "b", 1, "c", 1), plan.after());

		ZonePlan floor = ZonePlan.of(zones("a", 3, "b", 1, "c", 2), 2, 1, 0.1);
		assertEquals(3, floor.total(), "존 셋에 최소 1이면 셋 아래로는 줄이지 않는다");
	}

	/** 노드 다섯 중 가장 많은 존(a, 3개)이 빠지면 2개가 남는다. 수위 30%는 노드 수에 반비례해 75%가 된다. */
	@Test
	void checksTheLevelWhenTheLargestZoneIsLost() {
		ZonePlan plan = ZonePlan.of(zones("a", 3, "b", 2), 5, 1, 0.3);
		assertEquals("a", plan.lostZone());
		assertEquals(0.75, plan.levelIfLost(), 1e-9);

		ZonePlan single = ZonePlan.of(zones("a", 2), 2, 1, 0.3);
		assertEquals(Double.POSITIVE_INFINITY, single.levelIfLost());
	}

	@Test
	void parsesZonesInTheGivenOrder() {
		assertEquals(zones("b", 1, "a", 2), ZonePlan.parse("b=1, a=2"));
		assertThrows(IllegalArgumentException.class, () -> ZonePlan.parse("a=1,a=2"));
		assertThrows(IllegalArgumentException.class, () -> ZonePlan.parse("a"));
		assertThrows(IllegalArgumentException.class, () -> ZonePlan.parse("a=-1"));
	}
}
