package io.dbreplay.sandbox;

import java.util.function.LongSupplier;

/** 어떤 값이 더 줄지 않을 때까지 기다린다. 되돌린 뒤 서버가 조용해졌는지를 볼 때 쓴다. */
final class Settle {

	private Settle() {
	}

	/**
	 * 값이 0이 되거나, stableChecks번 연속으로 직전 값의 99% 아래로 내려가지 않으면 멈춘 것으로 본다.
	 *
	 * @return 멈췄으면 true. 시간 안에 멈추지 않았으면 false
	 */
	static boolean untilStable(LongSupplier probe, long pollMillis, long timeoutMillis, int stableChecks)
			throws InterruptedException {
		long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
		long previous = probe.getAsLong();
		int stable = 0;
		while (previous > 0 && stable < stableChecks) {
			if (System.nanoTime() >= deadline) {
				return false;
			}
			Thread.sleep(pollMillis);
			long now = probe.getAsLong();
			stable = now >= previous * 0.99 ? stable + 1 : 0;
			previous = now;
		}
		return true;
	}
}
