package io.dbreplay.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.dbreplay.replay.JdbcTarget;

class SandboxTest {

	@Test
	void waitsUntilTheValueStopsFalling() throws InterruptedException {
		Iterator<Long> dirtyPages = List.of(9_000L, 6_000L, 2_000L, 400L, 398L, 398L, 399L, 398L).iterator();
		AtomicInteger asked = new AtomicInteger();

		boolean settled = Settle.untilStable(() -> {
			asked.incrementAndGet();
			return dirtyPages.next();
		}, 1, 5_000, 3);

		assertTrue(settled);
		assertEquals(7, asked.get(), "400 뒤로 세 번 연속 1%도 줄지 않으면 멈춘 것이다");
	}

	@Test
	void stopsAtOnceWhenThereIsNothingLeft() throws InterruptedException {
		assertTrue(Settle.untilStable(() -> 0, 1, 5_000, 3));
	}

	@Test
	void givesUpWhenTheValueKeepsFalling() throws InterruptedException {
		long[] value = {1_000_000_000L};

		assertFalse(Settle.untilStable(() -> value[0] = (long) (value[0] * 0.9), 5, 60, 3));
	}

	@Test
	void readsWhereTheClientToolsShouldConnect() {
		assertEquals(new JdbcTarget.Endpoint("mysql84", 3306, "sbtest"),
				new JdbcTarget("jdbc:mysql://mysql84:3306/sbtest?sslMode=DISABLED", "u", "p").endpoint());
		assertEquals(new JdbcTarget.Endpoint("pg16", 5432, "postgres"),
				new JdbcTarget("jdbc:postgresql://pg16/postgres?preferQueryMode=simple", "u", "p").endpoint());
		assertNull(new JdbcTarget("jdbc:mysql://db:3307/?x=y", "u", "p").endpoint().database());
		assertEquals(3307, new JdbcTarget("jdbc:mysql://db:3307/?x=y", "u", "p").endpoint().port());
		assertThrows(IllegalArgumentException.class, () -> new JdbcTarget("jdbc:h2:mem:x", "u", "p").endpoint());
	}

	/** JDBC URL에는 암호를 넣어 쓸 수 있다. 읽지 못한 URL을 알려 줄 때 URL의 어느 부분도 따라 나오면 안 된다. */
	@Test
	void keepsTheUrlOutOfTheErrorMessage() {
		for (String url : List.of("jdbc:h2:mem:x?user=root&password=secret", "jdbc:h2://root:secret@host:9092/x",
				"jdbc:h2://root:p?secret@host/x")) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new JdbcTarget(url, "u", "p").endpoint());

			assertFalse(e.getMessage().contains("secret"), url);
			assertFalse(e.getMessage().contains("h2"), "URL의 어느 부분도 싣지 않는다");
		}
	}
}
