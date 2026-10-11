package io.dbreplay.explore;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.dbreplay.replay.JdbcTarget;

/**
 * 대상의 버전과 핵심 파라미터를 읽는다. 평가를 한 뒤 이것이 바뀌면 평가는 무효다.
 *
 * <p>장비의 CPU와 메모리는 DB 접속으로 알 수 없어 여기에 없다.
 */
public final class TargetProfile {

	private static final List<String> MYSQL = List.of(
			"innodb_buffer_pool_size", "innodb_buffer_pool_instances", "innodb_flush_log_at_trx_commit", "sync_binlog",
			"innodb_io_capacity", "innodb_io_capacity_max", "innodb_redo_log_capacity", "innodb_flush_method",
			"innodb_thread_concurrency", "max_connections", "table_open_cache", "transaction_isolation");
	private static final List<String> POSTGRES = List.of(
			"shared_buffers", "work_mem", "effective_cache_size", "max_connections", "synchronous_commit", "fsync",
			"full_page_writes", "wal_level", "max_wal_size", "checkpoint_timeout", "random_page_cost",
			"default_transaction_isolation");

	private TargetProfile() {
	}

	public static Map<String, String> read(JdbcTarget target) throws SQLException {
		Map<String, String> profile = new TreeMap<>();
		boolean postgres = target.isPostgres();
		List<String> names = postgres ? POSTGRES : MYSQL;
		String in = "'" + String.join("', '", names) + "'";
		String query = postgres
				? "SELECT name, setting FROM pg_settings WHERE name IN (" + in + ")"
				: "SELECT VARIABLE_NAME, VARIABLE_VALUE FROM performance_schema.global_variables WHERE VARIABLE_NAME IN (" + in + ")";
		try (Connection connection = target.connect(null); Statement statement = connection.createStatement()) {
			try (ResultSet rows = statement.executeQuery(postgres ? "SHOW server_version" : "SELECT VERSION()")) {
				rows.next();
				profile.put("version", rows.getString(1));
			}
			try (ResultSet rows = statement.executeQuery(query)) {
				while (rows.next()) {
					profile.put(rows.getString(1), rows.getString(2));
				}
			}
		}
		return profile;
	}
}
