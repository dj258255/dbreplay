package io.dbreplay.replay;

import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 대상 연결을 돌려 쓴다. 트랜잭션이나 문장 하나가 끝나면 연결을 돌려놓고 다음 것이 가져간다.
 *
 * <p>동시에 쓰는 연결 수에 상한을 둔다. 대상이 느려져 실행 중인 단위가 쌓이면 상한에 닿고, 그 뒤에 온
 * 단위는 연결이 날 때까지 기다린다. 애플리케이션의 연결 풀이 하는 일과 같다.
 *
 * <p>동시에 여는 연결의 수도 묶는다. 쉬는 연결이 없을 때 저마다 새 연결을 열게 두면, 여는 동안 온 단위도 또 열고
 * 동시에 여는 것이 많을수록 하나를 여는 데 더 오래 걸린다. 실험 24에서 16배속의 읽기에 필요한 연결은 동시에 9개 남짓인데
 * 라운드마다 상한인 500개를 다 열었고, 하나를 여는 데 중앙값 14~123ms가 걸렸다(#119). 묶인 수만큼 이미 열고 있으면
 * 새로 열지 않고 돌아오는 연결을 기다린다(#121).
 */
final class SessionPool {

	/**
	 * 동시에 여는 연결의 상한. 측정의 근거는 없다. 열기끼리 다투지 않을 만큼 작고, 대상이 진짜로 느려져 연결이 더 필요할 때
	 * 1초에 수백 개는 늘 수 있을 만큼 크게 Claude가 골랐다(#121, 범수 님 확인 전)
	 */
	static final int MAX_OPENING = 4;
	/** 쉬는 연결을 이만큼 기다리고 다시 본다. 그 사이에 여는 자리가 났을 수 있다. 근거는 위와 같다 */
	private static final long WAIT_NANOS = 1_000_000;

	private final Target target;
	// 연결이 데이터베이스를 바꿀 수 있으면 칸을 하나만 둔다. 첫 레코드의 데이터베이스로 미리 연 연결을 다른 데이터베이스의 문장도 쓴다(#141)
	private final boolean oneBucket;
	private final Semaphore inUse;
	private final Semaphore opening = new Semaphore(MAX_OPENING);
	private final Map<String, BlockingDeque<TargetSession>> idle = new ConcurrentHashMap<>();
	private final AtomicLong opened = new AtomicLong();

	SessionPool(Target target, int maxConnections) {
		this.target = target;
		this.oneBucket = target.switchesDatabase();
		this.inUse = new Semaphore(maxConnections);
	}

	/** @return 연결. 새로 열다가 실패했으면 null */
	TargetSession acquire(String db) throws InterruptedException {
		inUse.acquire();
		BlockingDeque<TargetSession> sessions = idleFor(db);
		while (true) {
			TargetSession session = sessions.pollFirst();
			if (session != null) {
				return session;
			}
			if (opening.tryAcquire()) {
				// 연 쪽이 제가 연 연결을 쓴다. 그래서 못 열면 그 단위가 실패로 적히는 것은 묶기 전과 같다
				try {
					session = target.open(db);
					opened.incrementAndGet();
					return session;
				}
				catch (Exception e) {
					inUse.release();
					return null;
				}
				finally {
					opening.release();
				}
			}
			session = sessions.pollFirst(WAIT_NANOS, TimeUnit.NANOSECONDS);
			if (session != null) {
				return session;
			}
		}
	}

	/**
	 * 재생을 시작하기 전에 연결을 몇 개 열어 둔다. 비어 있는 풀로 시작하면 첫 연결이 열리는 동안 도착한
	 * 단위들이 저마다 새 연결을 열어, 실험 03의 문장 단위 재생에서 연결이 상한인 500개까지 열렸다.
	 */
	void prewarm(String db, int count) {
		for (int i = 0; i < count; i++) {
			try {
				idleFor(db).addFirst(target.open(db));
				opened.incrementAndGet();
			}
			catch (Exception e) {
				return; // 못 열면 그만둔다. 재생 중에 다시 시도하고 그때의 실패는 문장의 실패로 적힌다
			}
		}
	}

	void release(String db, TargetSession session) {
		idleFor(db).addFirst(session);
		inUse.release();
	}

	long opened() {
		return opened.get();
	}

	void closeAll() {
		idle.values().forEach(sessions -> {
			for (TargetSession s; (s = sessions.pollFirst()) != null;) {
				s.close();
			}
		});
	}

	// PostgreSQL은 연결이 데이터베이스 하나에 묶여서 데이터베이스별로 따로 모은다
	private BlockingDeque<TargetSession> idleFor(String db) {
		return idle.computeIfAbsent(oneBucket || db == null ? "" : db, k -> new LinkedBlockingDeque<>());
	}
}
