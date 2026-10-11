package io.dbreplay.metrics;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import io.dbreplay.replay.JdbcTarget;

/**
 * DB 접속만으로 읽을 수 있는 부하 지표. 두 기종에서 같은 뜻으로 읽히는 둘만 둔다.
 *
 * <ul>
 * <li>{@code active_sessions}: 지금 문장을 실행 중인 연결 수. 이 표본기의 연결은 뺀다
 * <li>{@code slow_running}: 그중 시작한 지 기준 시간을 넘긴 문장 수
 * </ul>
 *
 * <p>CPU와 Load Average는 DB 접속으로 읽을 수 없다. {@link CommandMetricSource}로 받는다.
 */
public final class DbMetricSource implements MetricSource {

	public static final String ACTIVE_SESSIONS = "active_sessions";
	public static final String SLOW_RUNNING = "slow_running";

	// TIMER_WAIT는 피코초다. 실행 중인 문장은 조회하는 순간까지 흐른 시간이 들어 있다
	private static final String MYSQL = """
			SELECT (SELECT VARIABLE_VALUE - 1 FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Threads_running'),
			       (SELECT COUNT(*) FROM performance_schema.events_statements_current
			         WHERE END_EVENT_ID IS NULL AND TIMER_WAIT > ? * 1000000000)""";
	private static final String POSTGRES = """
			SELECT count(*), count(*) FILTER (WHERE now() - query_start > ? * interval '1 millisecond')
			  FROM pg_stat_activity
			 WHERE state = 'active' AND backend_type = 'client backend' AND pid <> pg_backend_pid()""";

	private final Connection connection;
	private final PreparedStatement statement;

	/** @param slowMillis 실행 중인 문장을 느리다고 볼 기준 시간 */
	public DbMetricSource(JdbcTarget target, long slowMillis) throws SQLException {
		this.connection = target.connect(null);
		this.statement = connection.prepareStatement(target.isPostgres() ? POSTGRES : MYSQL);
		this.statement.setLong(1, slowMillis);
	}

	@Override
	public Map<String, Double> read() throws SQLException {
		try (ResultSet rows = statement.executeQuery()) {
			rows.next();
			return Map.of(ACTIVE_SESSIONS, rows.getDouble(1), SLOW_RUNNING, rows.getDouble(2));
		}
	}

	@Override
	public void close() {
		try {
			connection.close();
		}
		catch (SQLException ignored) {
			// 닫는 중의 오류는 결과에 영향이 없다
		}
	}
}
