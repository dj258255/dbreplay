package io.dbreplay.metrics;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.dbreplay.replay.JdbcTarget;

/**
 * DB 접속만으로 읽을 수 있는 부하 지표. 기종마다 같은 뜻으로 읽히는 것과, 누적 값에서 내는 것을 둔다.
 *
 * <ul>
 * <li>{@code active_sessions}: 지금 문장을 실행 중인 연결 수. 이 표본기의 연결은 뺀다
 * <li>{@code slow_running}: 그중 시작한 지 기준 시간을 넘긴 문장 수
 * <li>{@code qps}: 직전 표본부터 지금까지의 초당 문장 수. PostgreSQL은 {@code pg_stat_statements}가 설치돼 있을 때만(#82)
 * <li>{@code tps}(PostgreSQL만): 직전 표본부터 지금까지의 초당 트랜잭션 수(커밋과 롤백)
 * <li>{@code slow_finished_total}(MySQL만): 표본을 뜨기 시작한 뒤 기준 시간을 넘겨 끝난 문장의 누적 수
 * </ul>
 *
 * <p>없는 값을 지어내지 않는다. PostgreSQL에는 문장 수를 세는 카운터가 기본으로 없다. 확장 {@code pg_stat_statements}가
 * 있으면 그 {@code calls}의 합으로 {@code qps}를 내고, 없으면 내지 않는다.
 * MySQL의 {@code tps}도 내지 않는다({@code Com_commit}은 자동 커밋된 문장을 세지 않아 틀린 값이 된다).
 *
 * <p>CPU와 Load Average는 DB 접속으로 읽을 수 없다. {@link CommandMetricSource}로 받는다.
 */
public final class DbMetricSource implements MetricSource {

	public static final String ACTIVE_SESSIONS = "active_sessions";
	public static final String SLOW_RUNNING = "slow_running";
	public static final String QPS = "qps";
	public static final String TPS = "tps";
	public static final String SLOW_FINISHED_TOTAL = "slow_finished_total";

	// 실행 중인 연결은 스레드 목록에서 센다. Threads_running은 서버의 상주 스레드 하나(이벤트 스케줄러)를 함께 세서
	// 한가할 때도 1이었다(#41). 종 DB에 바이너리 로그를 보내는 스레드도 뺀다. 언제나 실행 중으로 잡힌다(실험 11).
	// 종 DB에서 뜰 때(#80)는 복제를 적용하는 스레드(thread/sql/replica_*)도 뺀다. 그 일은 복제 지연으로 본다
	// TIMER_WAIT는 피코초다. 실행 중인 문장은 조회하는 순간까지 흐른 시간이 들어 있다
	private static final String MYSQL = """
			/* dbreplay:sampler */ SELECT
			       (SELECT COUNT(*) FROM performance_schema.threads
			         WHERE TYPE = 'FOREGROUND' AND PROCESSLIST_ID <> CONNECTION_ID()
			           AND PROCESSLIST_COMMAND NOT IN ('Sleep', 'Daemon', 'Binlog Dump', 'Binlog Dump GTID')
			           AND NAME NOT LIKE 'thread/sql/replica\\_%'),
			       (SELECT COUNT(*) FROM performance_schema.events_statements_current
			         WHERE END_EVENT_ID IS NULL AND TIMER_WAIT > ? * 1000000000
			           AND THREAD_ID NOT IN (SELECT THREAD_ID FROM performance_schema.threads
			                                  WHERE PROCESSLIST_COMMAND LIKE 'Binlog Dump%' OR NAME LIKE 'thread/sql/replica\\_%'))""";
	private static final String POSTGRES = """
			/* dbreplay:sampler */ SELECT count(*), count(*) FILTER (WHERE now() - query_start > ? * interval '1 millisecond')
			  FROM pg_stat_activity
			 WHERE state = 'active' AND backend_type = 'client backend' AND pid <> pg_backend_pid()""";

	// 누적 값에서 내는 지표는 따로 준비한 조회로 읽는다. 이 조회가 막혀도 기존 두 지표는 계속 뜨게다
	private static final String MYSQL_RATE =
			"/* dbreplay:sampler */ SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Questions'";
	private static final String POSTGRES_RATE =
			"/* dbreplay:sampler */ SELECT xact_commit + xact_rollback FROM pg_stat_database WHERE datname = current_database()";
	// 확장이 없으면 준비가 실패하고 qps만 꺼진다. 모든 데이터베이스와 계정의 문장을 센다. 서버 전체의 부하라서다
	private static final String POSTGRES_STATEMENTS =
			"/* dbreplay:sampler */ SELECT COALESCE(SUM(calls), 0) FROM pg_stat_statements";
	// BUCKET_TIMER_HIGH는 피코초다. 서버의 분포는 칸 단위라 기준 시간을 정확히 가를 수 없다. 기준이 걸쳐 있는 칸까지
	// 센다(>=가 아니라 >다). 그러면 기준보다 조금 빠른 문장이 그 칸의 폭만큼 함께 세어지는데, 덜 세는 것보다 더 세는
	// 쪽이 안전하다. 알람이 덜 울려 과부하를 못 보는 쪽으로 틀리면 안 된다
	private static final String MYSQL_SLOW_FINISHED = """
			/* dbreplay:sampler */ SELECT COALESCE(SUM(COUNT_BUCKET), 0)
			  FROM performance_schema.events_statements_histogram_global WHERE BUCKET_TIMER_HIGH > ? * 1000000000""";

	private final Connection connection;
	private final PreparedStatement statement;
	private final PreparedStatement rateStatement;
	private final PreparedStatement slowFinishedStatement;
	private final PreparedStatement statementsStatement;
	private final Counters counters;

	/** @param slowMillis 실행 중인 문장을 느리다고 볼 기준 시간 */
	public DbMetricSource(JdbcTarget target, long slowMillis) throws SQLException {
		boolean postgres = target.isPostgres();
		this.connection = target.connect(null);
		// 기존 조회의 준비가 실패하면 이곳 자체를 쓸 수 없다. 지금처럼 던진다
		this.statement = connection.prepareStatement(postgres ? POSTGRES : MYSQL);
		this.statement.setLong(1, slowMillis);
		// 새 조회의 준비가 실패하면 그 지표만 끈다. 여기서 던지면 active_sessions까지 사라져, 그 지표에 건 알람이
		// 조용히 안 울리게 된다
		this.rateStatement = prepare(postgres ? POSTGRES_RATE : MYSQL_RATE, null);
		this.slowFinishedStatement = postgres ? null : prepare(MYSQL_SLOW_FINISHED, slowMillis);
		this.statementsStatement = postgres ? prepare(POSTGRES_STATEMENTS, null) : null;
		// 표본기 자신이 보내는 조회 수는 Counters가 표본마다 센다. 상수로 빼면 느리게 끝난 문장 조회가 꺼졌을 때
		// 실제보다 많이 빼게 된다
		List<Rate> rates = new ArrayList<>();
		if (rateStatement != null) {
			rates.add(new Rate(postgres ? TPS : QPS, new StatementCounter(rateStatement)));
		}
		if (statementsStatement != null) {
			rates.add(new Rate(QPS, new StatementCounter(statementsStatement)));
		}
		this.counters = new Counters(rates, slowFinishedStatement == null ? null : new StatementCounter(slowFinishedStatement));
	}

	/** 새 조회를 준비한다. 실패하면 null을 돌려준다(그 지표만 끈다). */
	private PreparedStatement prepare(String sql, Long slowMillis) {
		try {
			PreparedStatement prepared = connection.prepareStatement(sql);
			if (slowMillis != null) {
				prepared.setLong(1, slowMillis);
			}
			return prepared;
		}
		catch (SQLException e) {
			// 이 기종이 내지 못하거나 권한이 없다. 그 지표만 끄고 생성자는 끝까지 간다
			return null;
		}
	}

	@Override
	public Map<String, Double> read() throws SQLException {
		Map<String, Double> values = new HashMap<>();
		try (ResultSet rows = statement.executeQuery()) {
			rows.next();
			values.put(ACTIVE_SESSIONS, rows.getDouble(1));
			values.put(SLOW_RUNNING, rows.getDouble(2));
		}
		counters.addTo(values, System.nanoTime());
		return values;
	}

	@Override
	public List<String> failures() {
		return counters.failures();
	}

	/** 누적 값을 한 번 읽는 곳. 표본기 자신의 PreparedStatement를 감싼 것이고, 테스트에서는 가짜를 넣는다. */
	interface Counter {
		double read() throws SQLException;
	}

	/**
	 * 누적 값 둘(초당 횟수용, 느리게 끝난 문장용)을 읽어 {@code values}에 더한다. 읽다 실패한 것은 끄고 다시
	 * 부르지 않는다. 다시 부르면 {@code read()}가 예외를 던져 표본기가 이 곳을 통째로 멈추고 {@code active_sessions}
	 * 까지 사라진다. 어느 한쪽이 없어도({@code null}) 그 기종이 내지 못하는 지표이므로 건너뛴다.
	 */
	/** 초당 횟수를 낼 누적 값 하나. 이름과 읽는 곳, 직전 표본의 상태를 든다. */
	static final class Rate {

		final String name;
		final Counter counter;
		boolean produced;
		boolean broken;
		Double previous;
		long previousNanos;

		Rate(String name, Counter counter) {
			this.name = name;
			this.counter = counter;
		}
	}

	static final class Counters {

		private final List<Rate> rates;
		private final Counter slowFinished;
		private final List<String> failures = new ArrayList<>();

		private boolean slowProduced;
		private boolean slowFinishedBroken;
		private boolean slowSeen;
		private long previousSlowRaw;
		private long slowSum;

		Counters(String rateName, Counter rate, Counter slowFinished) {
			this(rate == null ? List.of() : List.of(new Rate(rateName, rate)), slowFinished);
		}

		/** @param rates 초당 횟수를 낼 누적 값들. PostgreSQL은 tps와 qps 둘일 수 있다(#82) */
		Counters(List<Rate> rates, Counter slowFinished) {
			this.rates = List.copyOf(rates);
			this.slowFinished = slowFinished;
		}

		List<String> failures() {
			return List.copyOf(failures);
		}

		void addTo(Map<String, Double> values, long nanos) {
			// 이번 표본에서 자신이 보탠 조회 수는 읽기 전에 정한다. 이번에 끊긴 조회도 이번에는 보냈다
			double own = ownCount();
			for (Rate rate : rates) {
				addRate(rate, values, nanos, own);
			}
			addSlowFinished(values);
		}

		/**
		 * 두 표본 사이에 이 연결이 보낸 조회 수. 기존 조회 하나 + 켜진 누적 값 조회마다 하나다. 꺼진 조회를 세면
		 * 실제보다 많이 빼게 된다. 조회 하나는 자동 커밋이라 트랜잭션도 하나다.
		 */
		private double ownCount() {
			double own = 1;
			for (Rate rate : rates) {
				if (!rate.broken) {
					own++;
				}
			}
			if (slowFinished != null && !slowFinishedBroken) {
				own++;
			}
			return own;
		}

		/** 직전 표본과 지금 표본의 누적 값 차이로 초당 횟수를 낸다. 첫 표본에는 직전 값이 없어 내지 않는다. */
		private void addRate(Rate rate, Map<String, Double> values, long nanos, double own) {
			if (rate.broken) {
				return;
			}
			double current;
			try {
				current = rate.counter.read();
			}
			catch (SQLException e) {
				rate.broken = true;
				// 처음부터 실패한 것은 그 지표가 원래 없는 것이다. 값을 낸 뒤 끊긴 것만 적는다
				if (rate.produced) {
					failures.add(rate.name + " 조회가 도중에 실패했다 (" + state(e) + ")");
				}
				return;
			}
			if (rate.previous != null) {
				values.put(rate.name, rate(rate.previous, rate.previousNanos, current, nanos, own));
				rate.produced = true;
			}
			rate.previous = current;
			rate.previousNanos = nanos;
		}

		/**
		 * 표본을 뜨기 시작한 뒤 기준 시간을 넘겨 끝난 문장의 누적 수. 첫 표본은 0이다. 서버의 값이 늘었으면 늘어난
		 * 만큼 더하고, 줄었으면(서버가 분포를 비웠다) 지금의 서버 값만큼 더한다(비운 뒤 다시 쌓인 것이다). 이렇게
		 * 하면 이 지표는 줄지 않고, 분포를 비우기 전에 센 수도 사라지지 않는다.
		 */
		private void addSlowFinished(Map<String, Double> values) {
			if (slowFinished == null || slowFinishedBroken) {
				return;
			}
			long raw;
			try {
				raw = (long) slowFinished.read();
			}
			catch (SQLException e) {
				slowFinishedBroken = true;
				if (slowProduced) {
					failures.add(SLOW_FINISHED_TOTAL + " 조회가 도중에 실패했다 (" + state(e) + ")");
				}
				return;
			}
			if (slowSeen) {
				slowSum += raw >= previousSlowRaw ? raw - previousSlowRaw : raw;
			}
			slowSeen = true;
			previousSlowRaw = raw;
			values.put(SLOW_FINISHED_TOTAL, (double) slowSum);
			slowProduced = true;
		}

		/** SQLState가 있으면 그것을, 없으면 예외의 클래스 이름을 쓴다. 메시지는 싣지 않는다(접속 정보가 섞일 수 있다). */
		private static String state(SQLException e) {
			return e.getSQLState() != null ? e.getSQLState() : e.getClass().getSimpleName();
		}
	}

	/** PreparedStatement 하나를 Counter로 감싼다. */
	private static final class StatementCounter implements Counter {

		private final PreparedStatement statement;

		StatementCounter(PreparedStatement statement) {
			this.statement = statement;
		}

		@Override
		public double read() throws SQLException {
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getDouble(1);
			}
		}
	}

	/**
	 * 두 표본 사이의 초당 횟수. {@code own}은 그 사이에 표본기 자신이 보탠 횟수다.
	 *
	 * <p>시간이 0이거나 거꾸로면 0. 서버가 다시 떠서 누적 값이 줄었으면({@code current < previous}) 0. 자신이
	 * 보탠 것이 차이보다 크면 0으로 한다(음수를 내지 않는다).
	 */
	static double rate(double previous, long previousNanos, double current, long nanos, double own) {
		long elapsed = nanos - previousNanos;
		if (elapsed <= 0 || current < previous) {
			return 0;
		}
		double delta = current - previous - own;
		if (delta < 0) {
			return 0;
		}
		return delta / (elapsed / 1e9);
	}

	@Override
	public void close() {
		close(rateStatement);
		close(slowFinishedStatement);
		close(statementsStatement);
		close(statement);
		try {
			connection.close();
		}
		catch (SQLException ignored) {
			// 닫는 중의 오류는 결과에 영향이 없다
		}
	}

	private static void close(PreparedStatement statement) {
		if (statement == null) {
			return;
		}
		try {
			statement.close();
		}
		catch (SQLException ignored) {
			// 닫는 중의 오류는 결과에 영향이 없다
		}
	}
}