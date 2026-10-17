package io.dbreplay.explore;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ManagedTargetsTest {

	private static TrafficModel model(long statements, double selectShare) {
		return new TrafficModel(statements, 10_000_000, Map.of("SELECT", selectShare, "UPDATE", 1 - selectShare));
	}

	/** 모양이 안정적이고 부하가 있으면 든다. 하나라도 크게 바뀌었거나 부하가 작거나 파일이 하나뿐이면 들지 않는다 (#87). */
	@Test
	void registersOnlyStableBusyTargets() {
		assertNull(ManagedTargets.notRegisteredBecause(List.of(model(10_000, 0.8), model(11_000, 0.78)), 0.2, 500));
		assertTrue(ManagedTargets.notRegisteredBecause(List.of(model(10_000, 0.8), model(10_000, 0.2)), 0.2, 0)
				.startsWith("트래픽 모양이 안정적이지 않다"));
		assertTrue(ManagedTargets.notRegisteredBecause(List.of(model(10_000, 0.8), model(1_000, 0.8)), 0.2, 500)
				.startsWith("부하가 기준보다 작다"));
		assertTrue(ManagedTargets.notRegisteredBecause(List.of(model(10_000, 0.8)), 0.2, 0).contains("둘 미만"));
	}
}
