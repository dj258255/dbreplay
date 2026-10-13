package io.dbreplay.explore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
 * <p>장비의 CPU와 메모리는 DB 접속으로 알 수 없다. 사용자가 준 명령의 출력에서 받아 {@code host.} 접두사를
 * 붙여 더한다. 접두사를 붙이는 이유는 DB의 파라미터 이름과 섞이지 않게 하려는 것이다.
 */
public final class TargetProfile {

	/** 사용자가 준 명령이 낸 항목에 붙는 접두사. 비교할 때 DB 파라미터와 가려내는 데도 쓴다. */
	public static final String HOST_PREFIX = "host.";

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

	/**
	 * 사용자가 준 명령의 출력을 읽어 장비의 설정을 낸다. 한 줄에 {@code 이름 값} 또는 {@code 이름=값}이다.
	 * 처음 나오는 공백이나 {@code =}에서 가르고, 값의 앞뒤 공백은 뗀다. 값에는 공백이 있어도 된다
	 * ({@code model Apple M3 Pro}). 빈 줄, {@code #} 주석, 이름만 있고 값이 없는 줄은 버린다.
	 *
	 * <p>열쇠에는 {@code host.}를 붙인다. DB의 파라미터 이름과 섞이지 않게 하려는 것이다.
	 */
	static Map<String, String> parseHost(BufferedReader output) throws IOException {
		Map<String, String> profile = new TreeMap<>();
		for (String line; (line = output.readLine()) != null;) {
			String trimmed = line.strip();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			String[] parts = trimmed.split("[=\\s]+", 2);
			if (parts.length < 2 || parts[0].isEmpty() || parts[1].strip().isEmpty()) {
				continue;
			}
			profile.put(HOST_PREFIX + parts[0], parts[1].strip());
		}
		return profile;
	}

	/**
	 * {@code sh -c <명령>}을 실행해 표준 출력에서 장비 설정을 읽는다. 표준 오류는 버린다.
	 *
	 * @throws IOException 종료 코드가 0이 아니거나 문제가 있을 때. 명령의 원문(암호가 들었을 수 있다)은 메시지에 싣지 않는다
	 */
	public static Map<String, String> readHost(String command) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("sh", "-c", command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		Map<String, String> profile;
		try (BufferedReader output = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			profile = parseHost(output);
		}
		int exit = process.waitFor();
		if (exit != 0) {
			throw new IOException("설정 명령이 실패했다 (종료 코드 " + exit + ")");
		}
		return profile;
	}
}
