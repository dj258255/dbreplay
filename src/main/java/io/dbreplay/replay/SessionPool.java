package io.dbreplay.replay;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 대상 연결을 돌려 쓴다. 트랜잭션이나 문장 하나가 끝나면 연결을 돌려놓고 다음 것이 가져간다.
 *
 * <p>동시에 쓰는 연결 수에 상한을 둔다. 대상이 느려져 실행 중인 단위가 쌓이면 상한에 닿고, 그 뒤에 온
 * 단위는 연결이 날 때까지 기다린다. 애플리케이션의 연결 풀이 하는 일과 같다.
 */
final class SessionPool {

	private final Target target;
	private final Semaphore inUse;
	private final Map<String, Deque<TargetSession>> idle = new ConcurrentHashMap<>();
	private final AtomicLong opened = new AtomicLong();

	SessionPool(Target target, int maxConnections) {
		this.target = target;
		this.inUse = new Semaphore(maxConnections);
	}

	/** @return 연결. 새로 열다가 실패했으면 null */
	TargetSession acquire(String db) throws InterruptedException {
		inUse.acquire();
		TargetSession session = idleFor(db).pollFirst();
		if (session != null) {
			return session;
		}
		try {
			session = target.open(db);
			opened.incrementAndGet();
			return session;
		}
		catch (Exception e) {
			inUse.release();
			return null;
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
	private Deque<TargetSession> idleFor(String db) {
		return idle.computeIfAbsent(db == null ? "" : db, k -> new ConcurrentLinkedDeque<>());
	}
}
