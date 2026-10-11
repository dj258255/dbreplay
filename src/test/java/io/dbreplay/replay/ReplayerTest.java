package io.dbreplay.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.dbreplay.trace.TraceHeader;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;
import io.dbreplay.trace.TraceWriter;

class ReplayerTest {

	/** 문장에 적힌 대로 움직이는 가짜 대상. "sleep=300"이면 300ms 걸리고 "fail"이 있으면 실패한다. */
	private static final class FakeTarget implements Target {

		private static final Pattern SLEEP = Pattern.compile("sleep=(\\d+)");

		final List<String> executed = Collections.synchronizedList(new ArrayList<>());
		final List<String> openedWith = Collections.synchronizedList(new ArrayList<>());
		final AtomicInteger opened = new AtomicInteger();
		final AtomicInteger closed = new AtomicInteger();
		boolean refuseConnections;

		@Override
		public TargetSession open(String db) throws Exception {
			openedWith.add(String.valueOf(db));
			if (refuseConnections) {
				throw new IllegalStateException("연결 거부");
			}
			int connection = opened.incrementAndGet();
			return new TargetSession() {
				@Override
				public int execute(String db, String sql) {
					executed.add(connection + ":" + sql);
					Matcher m = SLEEP.matcher(sql);
					if (m.find()) {
						try {
							Thread.sleep(Long.parseLong(m.group(1)));
						}
						catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
					return sql.contains("fail") ? 1146 : 0;
				}

				@Override
				public void close() {
					closed.incrementAndGet();
				}
			};
		}
	}

	private static final Replayer.Options FAST = new Replayer.Options(1, Replayer.Unit.SESSION, 500, 0, 200_000, 50_000);
	private static final Replayer.Options BY_TRANSACTION = FAST.withUnit(Replayer.Unit.TRANSACTION);
	private static final Replayer.Options BY_STATEMENT = FAST.withUnit(Replayer.Unit.STATEMENT);

	private static TraceRecord statement(String session, long startMillis, String sql) {
		return new TraceRecord(session, startMillis * 1_000, 100, sql, null, 0, 0L, null, null);
	}

	private static TraceRecord inTransaction(String session, long txn, long startMillis, String sql) {
		return new TraceRecord(session, startMillis * 1_000, 100, sql, null, 0, 0L, txn, null);
	}

	/** 가짜 대상이 받은 문장을 연결별로, 받은 순서대로. */
	private static java.util.Collection<List<String>> byConnection(FakeTarget target) {
		return target.executed.stream().collect(Collectors.groupingBy(s -> s.substring(0, s.indexOf(':')),
				Collectors.mapping(s -> s.substring(s.indexOf(':') + 1), Collectors.toList()))).values();
	}

	private static TraceReader trace(TraceRecord... records) throws IOException {
		StringWriter out = new StringWriter();
		try (TraceWriter writer = new TraceWriter(out, TraceHeader.of("mysql", "test"))) {
			for (TraceRecord r : records) {
				writer.write(r);
			}
		}
		return new TraceReader(new StringReader(out.toString()));
	}

	private record Run(ReplaySummary summary, Map<Long, ReplayResult> bySeq) {
	}

	private static Run run(FakeTarget target, Replayer.Options options, TraceRecord... records) throws IOException {
		List<ReplayResult> results = Collections.synchronizedList(new ArrayList<>());
		ReplaySummary summary = Replayer.run(trace(records), target, results::add, options);
		return new Run(summary, results.stream().collect(Collectors.toMap(ReplayResult::seq, Function.identity())));
	}

	@Test
	void replaysEachSessionInOrderOnItsOwnConnection() throws IOException {
		FakeTarget target = new FakeTarget();
		Run run = run(target, FAST,
				statement("A", 0, "a1"),
				statement("B", 5, "b1"),
				statement("A", 10, "a2"),
				statement("B", 15, "b2"),
				statement("A", 20, "a3"),
				TraceRecord.close("A", 30_000));

		Map<String, List<String>> byConnection = target.executed.stream()
				.collect(Collectors.groupingBy(s -> s.substring(0, 1),
						Collectors.mapping(s -> s.substring(2), Collectors.toList())));
		assertEquals(2, byConnection.size());
		assertTrue(byConnection.containsValue(List.of("a1", "a2", "a3")));
		assertTrue(byConnection.containsValue(List.of("b1", "b2")));
		assertEquals(2, target.opened.get());
		assertEquals(2, target.closed.get(), "종료 줄이 없는 연결도 끝에서 닫는다");
		assertEquals(5, run.summary().statements());
		assertEquals(2, run.summary().sessions());
		assertEquals(0, run.summary().errors());
	}

	@Test
	void opensEachConnectionOnTheDatabaseOfItsFirstStatement() throws IOException {
		FakeTarget target = new FakeTarget();
		run(target, FAST,
				new TraceRecord("A", 0, 100, "a1", "shop", 0, null, null, null),
				new TraceRecord("B", 1_000, 100, "b1", "other", 0, null, null, null));

		assertEquals(List.of("other", "shop"), target.openedWith.stream().sorted().toList());
	}

	@Test
	void swapsTheDatabasePartOfAPostgresUrl() {
		assertEquals("jdbc:postgresql://db:5432/shop?preferQueryMode=simple",
				JdbcTarget.withDatabase("jdbc:postgresql://db:5432/postgres?preferQueryMode=simple", "shop"));
		assertEquals("jdbc:postgresql://db:5432/shop", JdbcTarget.withDatabase("jdbc:postgresql://db:5432", "shop"));
		assertEquals("jdbc:postgresql://db/shop", JdbcTarget.withDatabase("jdbc:postgresql://db/", "shop"));
	}

	@Test
	void dividesTheOriginalGapsBySpeed() throws IOException {
		Run run = run(new FakeTarget(), new Replayer.Options(8, Replayer.Unit.SESSION, 500, 0, 200_000, 50_000),
				statement("A", 0, "a1"),
				statement("A", 800, "a2"));

		assertEquals(0, run.bySeq().get(0L).planMicros());
		assertEquals(100_000, run.bySeq().get(1L).planMicros());
		assertEquals(800_000, run.summary().traceSpanMicros());
		assertTrue(run.summary().elapsedMicros() < 600_000, "8배속이면 0.1초 남짓이어야 한다: " + run.summary().elapsedMicros());
	}

	/** 대상이 느려 밀린 문장과 재생기가 늦은 문장을 구분해 센다. */
	@Test
	void tellsASlowTargetFromALateReplayer() throws IOException {
		Run run = run(new FakeTarget(), FAST,
				statement("A", 0, "a1 sleep=300"),
				statement("A", 50, "a2"),
				statement("B", 50, "b1"));

		ReplayResult behind = run.bySeq().get(1L);
		assertTrue(behind.sessionBusy());
		assertTrue(behind.lagMicros() >= 200_000, "앞 문장이 300ms 걸렸으니 250ms쯤 밀린다: " + behind.lagMicros());

		ReplayResult independent = run.bySeq().get(2L);
		assertFalse(independent.sessionBusy(), "다른 연결의 문장은 밀리지 않는다");
		assertTrue(independent.lagMicros() < 50_000, "제때 나가야 한다: " + independent.lagMicros());

		assertEquals(1, run.summary().late());
		assertEquals(1, run.summary().lateSessionBusy());
		assertEquals(0.0, run.summary().replayerLateRatio());
	}

	@Test
	void runsEachTransactionOnOnePooledConnection() throws IOException {
		FakeTarget target = new FakeTarget();
		Run run = run(target, BY_TRANSACTION,
				inTransaction("A", 1, 0, "BEGIN"),
				inTransaction("A", 1, 5, "UPDATE t SET a = 1"),
				inTransaction("A", 1, 10, "COMMIT"),
				statement("A", 40, "SET autocommit = 1"),
				statement("A", 45, "SELECT 1"),
				inTransaction("A", 2, 80, "BEGIN"),
				inTransaction("A", 2, 85, "UPDATE t SET a = 2"),
				inTransaction("A", 2, 90, "COMMIT"));

		List<String> all = target.executed.stream().map(s -> s.substring(s.indexOf(':') + 1)).toList();
		assertEquals(List.of("BEGIN", "UPDATE t SET a = 1", "COMMIT", "SELECT 1", "BEGIN", "UPDATE t SET a = 2", "COMMIT"), all);
		assertEquals(1, target.opened.get(), "앞 단위가 끝난 뒤라 같은 연결을 돌려 쓴다");
		assertEquals(1, run.summary().skipped(), "돌려 쓰는 연결에 세션 설정을 남기지 않는다");
		assertEquals(7, run.summary().statements());
		assertEquals(1, target.closed.get());
	}

	/** 대상이 느려도 다음 트랜잭션은 제 시각에 다른 연결로 나간다. 연결 단위에서는 뒤로 밀린다. */
	@Test
	void startsTheNextTransactionOnTimeEvenWhenThePreviousOneIsStillRunning() throws IOException {
		TraceRecord[] trace = {
				inTransaction("A", 1, 0, "BEGIN"),
				inTransaction("A", 1, 5, "UPDATE t SET a = 1 /* sleep=300 */"),
				inTransaction("A", 1, 10, "COMMIT"),
				inTransaction("A", 2, 50, "BEGIN"),
				inTransaction("A", 2, 55, "COMMIT")};

		FakeTarget pooled = new FakeTarget();
		Run byTransaction = run(pooled, BY_TRANSACTION, trace);
		assertTrue(byTransaction.bySeq().get(3L).lagMicros() < 50_000, "제때 나가야 한다: " + byTransaction.bySeq().get(3L).lagMicros());
		assertEquals(2, pooled.opened.get());
		assertTrue(byTransaction.bySeq().get(2L).sessionBusy(), "같은 트랜잭션의 앞 문장을 기다린 것은 따로 센다");

		Run bySession = run(new FakeTarget(), FAST, trace);
		assertTrue(bySession.bySeq().get(3L).lagMicros() >= 200_000, "연결 단위에서는 밀린다: " + bySession.bySeq().get(3L).lagMicros());
	}

	@Test
	void opensAndClosesTransactionsTheTraceLeftImplicit() throws IOException {
		FakeTarget target = new FakeTarget();
		run(target, BY_TRANSACTION,
				// autocommit을 끈 연결의 트랜잭션: BEGIN 없이 시작한다
				inTransaction("A", 1, 0, "UPDATE t SET a = 1"),
				inTransaction("A", 1, 5, "COMMIT"),
				// 커밋 없이 끊긴 연결
				inTransaction("B", 1, 100, "BEGIN"),
				inTransaction("B", 1, 105, "UPDATE t SET a = 2"),
				TraceRecord.close("B", 110_000));

		List<String> all = target.executed.stream().map(s -> s.substring(s.indexOf(':') + 1)).toList();
		assertEquals(List.of("BEGIN", "UPDATE t SET a = 1", "COMMIT", "BEGIN", "UPDATE t SET a = 2", "ROLLBACK"), all);
	}

	@Test
	void sendsEveryStatementIndependentlyAndSkipsTransactionControl() throws IOException {
		FakeTarget target = new FakeTarget();
		Run run = run(target, BY_STATEMENT,
				inTransaction("A", 1, 0, "BEGIN"),
				inTransaction("A", 1, 5, "SELECT a FROM t /* sleep=200 */"),
				inTransaction("A", 1, 25, "SELECT b FROM t"),
				inTransaction("A", 1, 30, "COMMIT"));

		assertEquals(2, run.summary().statements());
		assertEquals(2, run.summary().skipped());
		assertEquals(2, target.opened.get(), "앞 문장이 안 끝났어도 다른 연결로 나간다");
		assertTrue(run.bySeq().get(2L).lagMicros() < 50_000);
		assertFalse(run.bySeq().get(2L).sessionBusy());
	}

	@Test
	void opensConnectionsBeforeTheClockStarts() throws IOException {
		FakeTarget target = new FakeTarget();
		Run run = run(target, new Replayer.Options(1, Replayer.Unit.STATEMENT, 500, 4, 200_000, 50_000),
				statement("A", 0, "SELECT 1"),
				statement("B", 1, "SELECT 2"));

		assertEquals(4, target.opened.get());
		assertEquals(4, target.closed.get());
		assertEquals(4, run.summary().sessions());
	}

	@Test
	void waitsForAConnectionWhenThePoolIsFull() throws IOException {
		FakeTarget target = new FakeTarget();
		Run run = run(target, BY_STATEMENT.withMaxConnections(1),
				statement("A", 0, "SELECT a FROM t /* sleep=300 */"),
				statement("B", 20, "SELECT b FROM t"));

		assertEquals(1, target.opened.get());
		ReplayResult waited = run.bySeq().get(1L);
		assertTrue(waited.lagMicros() >= 200_000, "연결이 날 때까지 기다린다: " + waited.lagMicros());
		assertFalse(waited.sessionBusy(), "연결을 기다린 것은 재생기 쪽의 한계다");
		assertEquals(1, run.summary().late() - run.summary().lateSessionBusy());
	}

	@Test
	void recordsTargetErrorsNextToOriginalErrors() throws IOException {
		Run run = run(new FakeTarget(), FAST,
				statement("A", 0, "ok"),
				statement("A", 5, "fail here"),
				new TraceRecord("A", 10_000, 100, "ok too", null, 1062, 0L, null, null));

		assertEquals(1146, run.bySeq().get(1L).error());
		assertEquals(0, run.bySeq().get(1L).origError());
		assertEquals(1062, run.bySeq().get(2L).origError());
		assertEquals(1, run.summary().errors());
		assertEquals(1, run.summary().origErrors());
	}

	@Test
	void countsEveryStatementAsFailedWhenTheTargetRefusesTheConnection() throws IOException {
		FakeTarget target = new FakeTarget();
		target.refuseConnections = true;
		Run run = run(target, FAST, statement("A", 0, "a1"), statement("A", 5, "a2"));

		assertEquals(2, run.summary().errors());
		assertEquals(TargetSession.UNKNOWN_ERROR, run.bySeq().get(0L).error());
	}
}
