package io.dbreplay.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class ScopeFilterTest {

	/** 거르지 않으면 모든 문장과 연결 종료를 지금처럼 남긴다. */
	@Test
	void keepsEverythingWhenNothingIsGiven() {
		ScopeFilter all = ScopeFilter.all();
		assertFalse(all.active());
		assertTrue(all.keeps("1", "other", "anyone"));
		assertTrue(all.keepsClose("never-seen"), "녹화 전부터 열려 있던 연결의 종료도 남긴다");
		assertNull(all.describe());
	}

	/** 데이터베이스를 모르는 문장은 버리지 않고 센다. 버리면 대상의 문장이 빠질 수 있다. */
	@Test
	void keepsStatementsWhoseDatabaseIsUnknown() {
		ScopeFilter scope = new ScopeFilter(Set.of("shop"), Set.of());
		assertTrue(scope.keeps("1", null, "app"));
		assertEquals(1, scope.unknownDatabase());
		assertEquals(0, scope.droppedStatements());
	}

	/** 남긴 문장이 없는 연결은 종료 줄도 뺀다. 남긴 연결은 종료 줄을 한 번만 남긴다. */
	@Test
	void dropsTheCloseOfASessionWithNothingKept() {
		ScopeFilter scope = new ScopeFilter(Set.of("shop"), Set.of());
		assertTrue(scope.keeps("1", "shop", "app"));
		assertFalse(scope.keeps("2", "billing", "app"));
		assertTrue(scope.keepsClose("1"));
		assertFalse(scope.keepsClose("2"));
		assertFalse(scope.keepsClose("1"), "종료한 연결은 잊는다");
		assertEquals(2, scope.droppedCloses());
	}

	/** 같은 조건은 순서와 상관없이 같은 글자가 된다. 머리말끼리 비교할 수 있게 하려는 것이다. */
	@Test
	void describesTheConditionInAStableOrder() {
		assertEquals("database=a,b;exclude-user=x,y", new ScopeFilter(Set.of("b", "a"), Set.of("y", "x")).describe());
		assertEquals("exclude-user=x", new ScopeFilter(Set.of(), Set.of("x")).describe());
	}
}
