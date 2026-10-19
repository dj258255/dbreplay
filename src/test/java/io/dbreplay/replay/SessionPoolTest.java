package io.dbreplay.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SessionPoolTest {

	private static final class Nothing implements TargetSession {
		@Override
		public int execute(String db, String sql) {
			return 0;
		}

		@Override
		public void close() {
		}
	}

	/**
	 * 여는 자리가 다 찼으면 새로 열지 않고 돌아온 연결을 받는다. 저마다 열게 두면 실험 24에서 라운드마다 500개를 다 열었다(#121).
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void waitsForAReturnedConnectionWhileTheOpeningSlotsAreFull() throws Exception {
		CountDownLatch slowOpen = new CountDownLatch(1);
		AtomicInteger opens = new AtomicInteger();
		SessionPool pool = new SessionPool(db -> {
			opens.incrementAndGet();
			slowOpen.await();
			return new Nothing();
		}, 500);

		List<CompletableFuture<TargetSession>> opening = new ArrayList<>();
		for (int i = 0; i < SessionPool.MAX_OPENING; i++) {
			opening.add(acquireLater(pool));
		}
		while (opens.get() < SessionPool.MAX_OPENING) {
			Thread.onSpinWait();
		}
		CompletableFuture<TargetSession> waiting = acquireLater(pool);
		Thread.sleep(50);
		assertEquals(SessionPool.MAX_OPENING, opens.get(), "자리가 찼는데 또 열었다");
		assertFalse(waiting.isDone());

		TargetSession returned = new Nothing();
		pool.release("db", returned);
		assertSame(returned, waiting.get(5, TimeUnit.SECONDS));
		assertEquals(SessionPool.MAX_OPENING, opens.get());

		slowOpen.countDown();
		for (CompletableFuture<TargetSession> f : opening) {
			f.get(5, TimeUnit.SECONDS);
		}
	}

	/** 못 열면 그 단위가 null을 받는다. 묶기 전과 같다. 자리와 허용 수는 돌려놓아 다음 단위가 다시 열 수 있다. */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	void returnsNullWhenOpeningFailsAndFreesTheSlot() throws Exception {
		AtomicInteger opens = new AtomicInteger();
		SessionPool pool = new SessionPool(db -> {
			if (opens.incrementAndGet() <= SessionPool.MAX_OPENING) {
				throw new IllegalStateException("접속 거부");
			}
			return new Nothing();
		}, 1);

		for (int i = 0; i < SessionPool.MAX_OPENING; i++) {
			assertNull(pool.acquire("db"));
		}
		TargetSession session = pool.acquire("db");
		assertEquals(SessionPool.MAX_OPENING + 1, opens.get());
		assertEquals(1, pool.opened());
		pool.release("db", session);
	}

	private static CompletableFuture<TargetSession> acquireLater(SessionPool pool) {
		CompletableFuture<TargetSession> f = new CompletableFuture<>();
		Thread.ofVirtual().start(() -> {
			try {
				f.complete(pool.acquire("db"));
			}
			catch (Throwable e) {
				f.completeExceptionally(e);
			}
		});
		return f;
	}
}
