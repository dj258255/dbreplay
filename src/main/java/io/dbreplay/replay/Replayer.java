package io.dbreplay.replay;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import io.dbreplay.convert.SqlWords;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;

/**
 * 트래픽 파일을 대상에 다시 튼다. 문장은 원래 간격을 배속으로 나눈 시각에 나간다.
 *
 * <p>무엇을 한 단위로 내보낼지는 {@link Unit}이 정한다. 메이투안 글은 주 DB의 SQL을 트랜잭션 단위로 묶고
 * 종 DB의 SQL은 하나씩 독립으로 내보낸다. 단위마다 가상 스레드 하나가 계획 시각에 깨어나 연결 풀에서
 * 연결을 가져다 쓴다. 대상이 느려져도 다음 단위는 제 시각에 출발하므로 부하가 스스로 줄지 않는다.
 *
 * <p>문장이 계획보다 늦게 나가는 이유는 둘이다. 같은 단위의 앞 문장이 아직 안 끝났거나, 재생기가 제때
 * 내보내지 못했거나. 둘을 따로 센다. 뒤의 것이 많으면 재생기의 동시 처리가 모자라 재생이 왜곡된 것이다.
 */
public final class Replayer {

	public enum Unit {
		/** 원본의 연결 하나를 대상 연결 하나에 그대로 옮긴다. 세션 상태가 보존되지만 대상이 느려지면 부하도 줄어든다 */
		SESSION,
		/** 트랜잭션 하나, 또는 트랜잭션 밖의 문장 하나가 한 단위다. 글에서 주 DB를 다루는 방식이다 */
		TRANSACTION,
		/** 문장 하나가 한 단위다. 글에서 종 DB의 읽기를 다루는 방식이다. 트랜잭션 제어 문장은 건너뛴다 */
		STATEMENT
	}

	/**
	 * @param speed               배속. 2면 원래 간격의 절반으로 튼다
	 * @param unit                한 단위로 내보낼 범위
	 * @param maxConnections      연결을 돌려 쓰는 방식에서 동시에 쓰는 연결 수의 상한
	 * @param warmConnections     연결을 돌려 쓰는 방식에서 시작하기 전에 열어 둘 연결 수
	 * @param lookaheadMicros     계획 시각보다 이만큼 먼저 문장을 실행 스레드에 넘긴다
	 * @param lateThresholdMicros 이보다 늦게 나가면 늦은 문장으로 센다
	 * @param readsOnly           읽기 문장만 튼다. 나머지는 건너뛴 것으로 센다
	 */
	public record Options(double speed, Unit unit, int maxConnections, int warmConnections, long lookaheadMicros,
			long lateThresholdMicros, boolean readsOnly) {

		public Options {
			if (!(speed > 0)) {
				throw new IllegalArgumentException("배속은 0보다 커야 한다: " + speed);
			}
		}

		public static Options atSpeed(double speed) {
			return new Options(speed, Unit.TRANSACTION, 500, 16, 1_000_000, 1_000, false);
		}

		public Options withUnit(Unit unit) {
			return new Options(speed, unit, maxConnections, warmConnections, lookaheadMicros, lateThresholdMicros, readsOnly);
		}

		/** 읽기만 튼다. 종 DB가 받는 읽기의 상한을 볼 때 쓴다. 문장 단위로 내보내야 뜻이 있다. */
		public Options onlyReads() {
			return new Options(speed, Unit.STATEMENT, maxConnections, warmConnections, lookaheadMicros, lateThresholdMicros, true);
		}

		public Options withMaxConnections(int maxConnections) {
			return new Options(speed, unit, maxConnections, Math.min(warmConnections, maxConnections), lookaheadMicros,
					lateThresholdMicros, readsOnly);
		}
	}

	private record Item(long seq, TraceRecord record, long planNanos) {
	}

	private static final Item END = new Item(-1, null, 0);
	private static final Set<String> SESSION_STATE = Set.of("SET", "USE", "RESET", "DISCARD");
	private static final Set<String> TRANSACTION_CONTROL = Set.of("BEGIN", "START", "COMMIT", "END", "ABORT", "ROLLBACK",
			"SAVEPOINT", "RELEASE");

	private final Target target;
	private final ResultSink sink;
	private final Options options;
	private final SessionPool pool;

	private final LongAdder statements = new LongAdder();
	private final LongAdder errors = new LongAdder();
	private final LongAdder origErrors = new LongAdder();
	private final LongAdder late = new LongAdder();
	private final LongAdder lateBusy = new LongAdder();
	private final Histogram lag = new Histogram();
	private final Histogram duration = new Histogram();
	private final Histogram origDuration = new Histogram();
	private final AtomicLong lastDoneNanos = new AtomicLong(Long.MIN_VALUE);
	private final AtomicLong sessionsOpened = new AtomicLong();
	private long skipped;
	private long originNanos;
	private SpinTimer timer;

	private Replayer(Target target, ResultSink sink, Options options) {
		this.target = target;
		this.sink = sink;
		this.options = options;
		this.pool = new SessionPool(target, options.maxConnections());
	}

	public static ReplaySummary run(TraceReader trace, Target target, ResultSink sink, Options options) throws IOException {
		return new Replayer(target, sink, options).run(trace);
	}

	private ReplaySummary run(TraceReader trace) throws IOException {
		long lookaheadNanos = options.lookaheadMicros() * 1_000;
		long seq = 0;
		long baseMicros = 0;
		long lastStartMicros = 0;

		try (SpinTimer spinTimer = new SpinTimer()) {
			timer = spinTimer;
			try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
				Dispatcher dispatcher = options.unit() == Unit.SESSION
						? new SessionDispatcher(executor)
						: new UnitDispatcher(executor);
				for (TraceRecord r; (r = trace.next()) != null; seq++) {
					if (seq == 0) {
						if (options.unit() != Unit.SESSION) {
							pool.prewarm(r.db(), options.warmConnections());
						}
						baseMicros = r.startMicros();
						originNanos = System.nanoTime() + lookaheadNanos;
					}
					lastStartMicros = r.startMicros();
					long planNanos = originNanos + Math.round((r.startMicros() - baseMicros) * 1_000.0 / options.speed());
					// 넘기는 시각은 정밀할 필요가 없다. 실행 스레드가 계획 시각을 타이머로 다시 맞춘다
					for (long wait; (wait = planNanos - lookaheadNanos - System.nanoTime()) > 0;) {
						LockSupport.parkNanos(wait);
					}
					if (options.readsOnly() && !r.endsSession() && !SqlWords.reads(r.sql())) {
						skipped++;
						continue;
					}
					dispatcher.accept(new Item(seq, r, planNanos));
				}
				dispatcher.finish();
			}
			pool.closeAll();
		}

		long elapsedMicros = statements.sum() == 0 ? 0 : (lastDoneNanos.get() - originNanos) / 1_000;
		long sessions = options.unit() == Unit.SESSION ? sessionsOpened.get() : pool.opened();
		return new ReplaySummary(statements.sum(), sessions, skipped, errors.sum(), origErrors.sum(), late.sum(),
				lateBusy.sum(), options.speed(), lastStartMicros - baseMicros, elapsedMicros, lag, duration, origDuration);
	}

	private void report(Item item, long sentNanos, long doneNanos, boolean busy, int error) {
		TraceRecord r = item.record();
		long lagMicros = Math.max(0, (sentNanos - item.planNanos()) / 1_000);
		long durationMicros = (doneNanos - sentNanos) / 1_000;

		statements.increment();
		lag.record(lagMicros);
		duration.record(durationMicros);
		origDuration.record(r.durationMicros());
		if (error != 0) {
			errors.increment();
		}
		if (r.error() != null && r.error() != 0) {
			origErrors.increment();
		}
		if (lagMicros > options.lateThresholdMicros()) {
			late.increment();
			if (busy) {
				lateBusy.increment();
			}
		}
		lastDoneNanos.accumulateAndGet(doneNanos, Math::max);
		sink.accept(new ReplayResult(item.seq(), r.session(), (item.planNanos() - originNanos) / 1_000, lagMicros,
				busy, durationMicros, error, r.durationMicros(), r.error()));
	}

	private static int execute(TargetSession session, TraceRecord r) {
		return session == null ? TargetSession.UNKNOWN_ERROR : session.execute(r.db(), r.sql());
	}

	private static boolean beginsTransaction(String[] words) {
		return words[0].equals("BEGIN") || (words[0].equals("START") && words.length > 1 && words[1].equals("TRANSACTION"));
	}

	private static boolean endsTransaction(String[] words) {
		String second = words.length > 1 ? words[1] : "";
		return Set.of("COMMIT", "END", "ABORT", "ROLLBACK").contains(words[0])
				&& !second.equals("TO") && !second.equals("PREPARED");
	}

	/** 트래픽 파일의 줄을 순서대로 받아 실행 스레드에 나눠 준다. 한 스레드만 부른다. */
	private interface Dispatcher {

		void accept(Item item);

		/** 줄이 더 없다. 열려 있는 것을 마무리한다. */
		void finish();
	}

	// ---- 연결 단위 ----

	private final class SessionDispatcher implements Dispatcher {

		private final ExecutorService executor;
		private final Map<String, SessionWorker> workers = new HashMap<>();

		SessionDispatcher(ExecutorService executor) {
			this.executor = executor;
		}

		@Override
		public void accept(Item item) {
			String sessionId = item.record().session();
			SessionWorker worker = workers.get(sessionId);
			if (worker == null) {
				worker = new SessionWorker();
				workers.put(sessionId, worker);
				executor.execute(worker);
			}
			worker.queue.add(item);
			if (item.record().endsSession()) {
				workers.remove(sessionId);
			}
		}

		@Override
		public void finish() {
			// 종료 줄 없이 끝난 연결은 마지막 문장을 마치는 대로 닫는다
			workers.values().forEach(w -> w.queue.add(END));
		}
	}

	private final class SessionWorker implements Runnable {

		final BlockingQueue<Item> queue = new LinkedBlockingQueue<>();

		@Override
		public void run() {
			TargetSession session = null;
			boolean opened = false;
			long freeAtNanos = System.nanoTime();
			try {
				while (true) {
					Item item = queue.take();
					if (item == END) {
						return;
					}
					if (!opened) {
						// 첫 문장을 계획 시각보다 먼저 넘겨받으므로 연결은 그 사이에 열린다
						opened = true;
						try {
							session = target.open(item.record().db());
							sessionsOpened.incrementAndGet();
						}
						catch (Exception e) {
							// 연결을 못 열었다. 이 연결의 문장은 전부 실패로 적는다
						}
						freeAtNanos = System.nanoTime();
					}
					boolean busy = freeAtNanos > item.planNanos();
					timer.sleepUntil(item.planNanos());
					if (item.record().endsSession()) {
						return;
					}
					long sentNanos = System.nanoTime();
					int error = execute(session, item.record());
					freeAtNanos = System.nanoTime();
					report(item, sentNanos, freeAtNanos, busy, error);
				}
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			finally {
				if (session != null) {
					session.close();
				}
			}
		}
	}

	// ---- 트랜잭션 단위와 문장 단위 ----

	private final class UnitDispatcher implements Dispatcher {

		private final ExecutorService executor;
		// 연결별로 지금 줄을 받고 있는 트랜잭션
		private final Map<String, TransactionUnit> open = new HashMap<>();

		UnitDispatcher(ExecutorService executor) {
			this.executor = executor;
		}

		@Override
		public void accept(Item item) {
			TraceRecord r = item.record();
			if (r.endsSession()) {
				close(r.session());
				return;
			}
			String[] words = SqlWords.head(r.sql());
			boolean sessionState = SESSION_STATE.contains(words[0]);

			if (options.unit() == Unit.STATEMENT) {
				if (sessionState || TRANSACTION_CONTROL.contains(words[0])) {
					skipped++; // 문장마다 연결이 달라서 세션 설정과 트랜잭션 제어는 뜻이 없다
				}
				else {
					executor.execute(() -> runAlone(item));
				}
				return;
			}

			if (r.txn() == null) {
				close(r.session());
				if (sessionState) {
					skipped++; // 돌려 쓰는 연결에 남의 세션 설정이 남으면 안 된다
				}
				else {
					executor.execute(() -> runAlone(item));
				}
				return;
			}
			TransactionUnit unit = open.get(r.session());
			if (unit == null || !unit.txn.equals(r.txn())) {
				close(r.session());
				unit = new TransactionUnit(r.txn(), beginsTransaction(words));
				open.put(r.session(), unit);
				executor.execute(unit);
			}
			unit.queue.add(item);
			if (endsTransaction(words)) {
				unit.queue.add(END);
				open.remove(r.session());
			}
		}

		private void close(String sessionId) {
			TransactionUnit unit = open.remove(sessionId);
			if (unit != null) {
				unit.queue.add(END);
			}
		}

		@Override
		public void finish() {
			open.values().forEach(unit -> unit.queue.add(END));
			open.clear();
		}
	}

	private void runAlone(Item item) {
		try {
			timer.sleepUntil(item.planNanos());
			String db = item.record().db();
			TargetSession session = pool.acquire(db);
			long sentNanos = System.nanoTime();
			int error = execute(session, item.record());
			long doneNanos = System.nanoTime();
			if (session != null) {
				pool.release(db, session);
			}
			report(item, sentNanos, doneNanos, false, error);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private final class TransactionUnit implements Runnable {

		final Long txn;
		final boolean startsExplicitly;
		final BlockingQueue<Item> queue = new LinkedBlockingQueue<>();

		TransactionUnit(Long txn, boolean startsExplicitly) {
			this.txn = txn;
			this.startsExplicitly = startsExplicitly;
		}

		@Override
		public void run() {
			TargetSession session = null;
			String db = null;
			boolean endedExplicitly = false;
			try {
				Item item = queue.take();
				db = item.record().db();
				timer.sleepUntil(item.planNanos());
				session = pool.acquire(db);
				if (session != null && !startsExplicitly) {
					// 원본은 autocommit을 끈 연결이었다. 돌려 쓰는 연결은 켜져 있으므로 직접 연다
					session.execute(db, "BEGIN");
				}
				long freeAtNanos = Long.MIN_VALUE;
				while (item != END) {
					boolean busy = freeAtNanos > item.planNanos();
					timer.sleepUntil(item.planNanos());
					long sentNanos = System.nanoTime();
					int error = execute(session, item.record());
					freeAtNanos = System.nanoTime();
					report(item, sentNanos, freeAtNanos, busy, error);
					endedExplicitly = endsTransaction(SqlWords.head(item.record().sql()));
					item = queue.take();
				}
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			finally {
				if (session != null) {
					if (!endedExplicitly) {
						// 원본에서 커밋 없이 끊겼거나 녹화가 중간에 끝났다. 열린 채로 풀에 돌려놓으면 안 된다
						session.execute(db, "ROLLBACK");
					}
					pool.release(db, session);
				}
			}
		}
	}
}
