package io.dbreplay.replay;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * 정해진 시각에 스레드를 깨운다. 전용 스레드 하나가 쉬지 않고 돌면서 시각을 본다.
 *
 * <p>운영체제의 타이머에 맡기면 깨어나는 시각이 밀린다. 도커 안에서 재 보니 가상 스레드는 중앙값 0.76ms,
 * 31%가 1ms 넘게 늦었다. 이 방식으로는 중앙값 0.03ms였다. 대가는 재생하는 동안 코어 하나를 다 쓰는 것이다.
 */
final class SpinTimer implements AutoCloseable {

	private static final class Waiter implements Comparable<Waiter> {
		final long deadlineNanos;
		final Thread thread;
		volatile boolean fired;

		Waiter(long deadlineNanos, Thread thread) {
			this.deadlineNanos = deadlineNanos;
			this.thread = thread;
		}

		@Override
		public int compareTo(Waiter other) {
			return Long.compare(deadlineNanos, other.deadlineNanos);
		}
	}

	private final PriorityBlockingQueue<Waiter> waiters = new PriorityBlockingQueue<>();
	private final Thread thread;
	private volatile boolean running = true;

	SpinTimer() {
		thread = Thread.ofPlatform().name("dbreplay-timer").daemon().start(this::spin);
	}

	private void spin() {
		while (running) {
			Waiter next = waiters.peek();
			if (next != null && next.deadlineNanos - System.nanoTime() <= 0) {
				// 본 것과 꺼낸 것이 다를 수 있다. 그 사이에 더 이른 대기자가 들어오면 그쪽이 나온다.
				// 꺼낸 쪽을 깨워야 한다. 본 쪽을 깨우면 꺼낸 쪽은 큐에서 빠진 채 영영 잠든다.
				Waiter due = waiters.poll();
				due.fired = true;
				LockSupport.unpark(due.thread);
			}
			else {
				Thread.onSpinWait();
			}
		}
	}

	/** 그 시각까지 쉰다. 이미 지났으면 바로 돌아온다. */
	void sleepUntil(long deadlineNanos) {
		if (deadlineNanos - System.nanoTime() <= 0) {
			return;
		}
		Waiter waiter = new Waiter(deadlineNanos, Thread.currentThread());
		waiters.add(waiter);
		while (!waiter.fired) {
			LockSupport.park(this);
		}
	}

	@Override
	public void close() {
		running = false;
	}
}
