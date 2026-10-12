package io.dbreplay.sandbox;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import io.dbreplay.replay.JdbcTarget;

/**
 * 종 DB가 주 DB의 지금까지의 변경을 다 적용할 때까지 기다린다.
 *
 * <p>샌드박스를 되돌리는 일은 주 DB에 덤프를 되붓는 것이고, 종 DB는 그것을 복제로 받는다. 종 DB가 아직
 * 따라오는 중에 재생을 시작하면 종 DB의 읽기가 되부은 데이터의 적용과 겹친다.
 */
public final class ReplicaSync {

	private ReplicaSync() {
	}

	/** @return 기다린 시간(밀리초) */
	public static long await(JdbcTarget primary, JdbcTarget replica, long timeoutSeconds) throws SQLException, InterruptedException {
		long started = System.nanoTime();
		try (Connection p = primary.connect(null); Connection r = replica.connect(null)) {
			if (primary.isPostgres()) {
				awaitLsn(p, r, timeoutSeconds);
			}
			else {
				awaitGtid(p, r, timeoutSeconds);
			}
		}
		return (System.nanoTime() - started) / 1_000_000;
	}

	private static void awaitGtid(Connection primary, Connection replica, long timeoutSeconds) throws SQLException {
		String executed = single(primary, "SELECT @@GLOBAL.gtid_executed").replace("\n", "");
		if (executed.isEmpty()) {
			throw new IllegalStateException("주 DB에 GTID가 없다. 종 DB가 따라왔는지 보려면 gtid_mode가 켜져 있어야 한다");
		}
		try (PreparedStatement wait = replica.prepareStatement("SELECT WAIT_FOR_EXECUTED_GTID_SET(?, ?)")) {
			wait.setString(1, executed);
			wait.setLong(2, timeoutSeconds);
			try (ResultSet rows = wait.executeQuery()) {
				rows.next();
				if (rows.getInt(1) != 0) {
					throw new IllegalStateException("종 DB가 " + timeoutSeconds + "초 안에 주 DB를 따라오지 못했다");
				}
			}
		}
	}

	private static void awaitLsn(Connection primary, Connection replica, long timeoutSeconds) throws SQLException, InterruptedException {
		String lsn = single(primary, "SELECT pg_current_wal_lsn()");
		long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
		try (PreparedStatement reached = replica.prepareStatement("SELECT pg_last_wal_replay_lsn() >= ?::pg_lsn")) {
			reached.setString(1, lsn);
			while (true) {
				try (ResultSet rows = reached.executeQuery()) {
					rows.next();
					if (rows.getBoolean(1)) {
						return;
					}
				}
				if (System.nanoTime() > deadline) {
					throw new IllegalStateException("종 DB가 " + timeoutSeconds + "초 안에 주 DB를 따라오지 못했다");
				}
				Thread.sleep(100);
			}
		}
	}

	private static String single(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
			rows.next();
			return rows.getString(1);
		}
	}
}
