package io.dbreplay.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SpinTimerTest {

	/**
	 * 많은 스레드가 뒤섞인 시각으로 잠들어도 하나도 빠짐없이, 제 시각 뒤에 깨어나야 한다.
	 * 실험 04에서 깨우는 쪽이 대기자를 잘못 꺼내 재생이 멈춘 적이 있다.
	 */
	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void wakesEveryWaiterAndNeverEarly() throws InterruptedException {
		int threads = 400;
		int rounds = 200;
		AtomicInteger woken = new AtomicInteger();
		AtomicLong earliest = new AtomicLong(Long.MAX_VALUE);

		try (SpinTimer timer = new SpinTimer()) {
			List<Thread> all = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				int id = i;
				all.add(Thread.ofVirtual().start(() -> {
					Random random = new Random(id);
					for (int k = 0; k < rounds; k++) {
						long deadline = System.nanoTime() + random.nextInt(2_000_000);
						timer.sleepUntil(deadline);
						earliest.accumulateAndGet(System.nanoTime() - deadline, Math::min);
						woken.incrementAndGet();
					}
				}));
			}
			for (Thread t : all) {
				t.join();
			}
		}

		assertEquals(threads * rounds, woken.get());
		assertTrue(earliest.get() >= 0, "계획 시각보다 먼저 깨면 안 된다: " + earliest.get());
	}

	@Test
	void returnsAtOnceForADeadlineAlreadyPassed() {
		try (SpinTimer timer = new SpinTimer()) {
			long before = System.nanoTime();
			timer.sleepUntil(before - 1_000_000);
			assertTrue(System.nanoTime() - before < 50_000_000);
		}
	}
}
