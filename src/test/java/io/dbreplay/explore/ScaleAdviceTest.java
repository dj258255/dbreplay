package io.dbreplay.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ScaleAdviceTest {

	/** 쓰기에 주 DB의 3.4배가 필요한데 2배까지만 키울 수 있으면 두 조각으로 나눈다. 한도 안이거나 모자라지 않으면 나누지 않는다 (#85). */
	@Test
	void splitsWhenTheWriteNeedExceedsTheScaleLimit() {
		ScaleAdvice shortOnWrites = new ScaleAdvice(2, 2, 3.4, false, WaterLevel.Verdict.HEALTHY, WaterLevel.Verdict.SHORT);
		assertEquals(2, shortOnWrites.shards(2));
		assertEquals(4, shortOnWrites.shards(1));
		assertEquals(1, shortOnWrites.shards(4));

		ScaleAdvice healthy = new ScaleAdvice(2, 2, 0.9, false, WaterLevel.Verdict.HEALTHY, WaterLevel.Verdict.HEALTHY);
		assertEquals(1, healthy.shards(1));

		assertThrows(IllegalArgumentException.class, () -> shortOnWrites.shards(0.5));
		assertThrows(IllegalArgumentException.class, () -> shortOnWrites.shards(Double.NaN));
	}
}
