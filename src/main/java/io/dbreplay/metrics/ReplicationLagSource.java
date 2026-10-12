package io.dbreplay.metrics;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import io.dbreplay.replay.JdbcTarget;

/**
 * 복제 지연. 종 DB가 주 DB의 변경을 얼마나 늦게 적용하고 있는지를 초로 낸다.
 *
 * <p>두 기종이 지연을 내주는 자리가 다르다.
 * <ul>
 * <li>MySQL은 종 DB에 묻는다. 적용 중인 트랜잭션이 주 DB에서 커밋된 시각과 지금의 차이다. 적용할 것이 없으면 0이다.
 * {@code Seconds_Behind_Source}는 1초 단위라 작은 지연이 보이지 않아서 쓰지 않는다. 두 서버의 시계가 다르면 값이 틀어진다
 * <li>PostgreSQL은 주 DB에 묻는다. {@code pg_stat_replication.replay_lag}는 주 DB가 잰 값이라 시계 차이를 타지 않는다
 * </ul>
 *
 * <p>복제가 돌고 있지 않으면 값을 내지 않는다. 0을 내면 지연이 없는 것으로 읽힌다.
 */
public final class ReplicationLagSource implements MetricSource {

	public static final String LAG_SECONDS = "replication_lag_seconds";

	private static final String MYSQL = """
			/* dbreplay:sampler */ SELECT
			  (SELECT COALESCE(MAX(TIMESTAMPDIFF(MICROSECOND, APPLYING_TRANSACTION_ORIGINAL_COMMIT_TIMESTAMP, NOW(6))), 0) / 1000000
			     FROM performance_schema.replication_applier_status_by_worker WHERE APPLYING_TRANSACTION <> ''),
			  (SELECT COUNT(*) FROM performance_schema.replication_applier_status WHERE SERVICE_STATE = 'ON')""";
	private static final String POSTGRES = """
			/* dbreplay:sampler */ SELECT COALESCE(MAX(EXTRACT(EPOCH FROM replay_lag)), 0), COUNT(*)
			  FROM pg_stat_replication WHERE state = 'streaming'""";

	private final Connection connection;
	private final PreparedStatement statement;

	public ReplicationLagSource(JdbcTarget primary, JdbcTarget replica) throws SQLException {
		this.connection = primary.isPostgres() ? primary.connect(null) : replica.connect(null);
		this.statement = connection.prepareStatement(primary.isPostgres() ? POSTGRES : MYSQL);
	}

	@Override
	public Map<String, Double> read() throws SQLException {
		try (ResultSet rows = statement.executeQuery()) {
			rows.next();
			if (rows.getLong(2) == 0) {
				return Map.of();
			}
			// 시계가 어긋나 음수가 나오면 0으로 본다
			return Map.of(LAG_SECONDS, Math.max(0, rows.getDouble(1)));
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
