package io.dbreplay.sandbox;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 샌드박스를 띄우고 파라미터를 옮길 때 기종마다 다른 것(#84). 이미지 이름, 버전과 파라미터를 읽는 조회, 영구 설정 문장,
 * 옮기면 안 되는 파라미터(장비와 경로, 서버의 정체성)를 한곳에 둔다.
 */
public enum Engine {

	MYSQL("mysql", 3306, "MYSQL_ROOT_PASSWORD", "SELECT VERSION()", "SHOW GLOBAL VARIABLES", List.of(
			"hostname", "server_uuid", "server_id", "port", "socket", "mysqlx_.*", "admin_.*", "datadir", "basedir", "tmpdir",
			"pid_file", "log_error", "general_log_file", "slow_query_log_file", "secure_file_priv", "plugin_dir", "character_sets_dir",
			"lc_messages_dir", "innodb_data_home_dir", "innodb_log_group_home_dir", "innodb_undo_directory", "innodb_temp_.*directory",
			"log_bin_basename", "log_bin_index", "relay_log.*", "report_.*", "ssl_.*", "tls_.*", ".*_path", "version.*", "license",
			"have_.*", "protocol_version", "lower_case_file_system", "system_time_zone", "gtid_executed", "gtid_purged",
			"gtid_owned", "timestamp", "innodb_buffer_pool_load_at_startup", "persisted_globals_load", "core_file",
			"large_files_support", "open_files_limit", "table_open_cache_instances", "innodb_read_io_threads",
			"innodb_write_io_threads", "innodb_buffer_pool_instances", "thread_handling", "skip_.*", "bind_address",
			"default_authentication_plugin", "authentication_policy", "caching_sha2_password_.*", "sha256_password_.*",
			"keyring_.*", "log_bin", "binlog_.*_file", ".*_uuid", "init_file", "upgrade")),

	POSTGRES("postgres", 5432, "POSTGRES_PASSWORD", "SHOW server_version",
			"SELECT name, setting FROM pg_settings WHERE context <> 'internal'", List.of(
			"data_directory", "config_file", "hba_file", "ident_file", "external_pid_file", "listen_addresses", "port",
			"unix_socket_.*", "ssl.*", "log_directory", "log_filename", "cluster_name", "krb_server_keyfile", "server_version.*",
			"lc_.*", "application_name", "client_encoding", "datestyle", "intervalstyle", "timezone", "log_timezone",
			"transaction_.*", "default_transaction_.*", "is_superuser", "session_authorization", "role", "search_path",
			"extra_float_digits", "data_directory_mode", "shared_memory_type", "dynamic_shared_memory_type", "huge_pages.*"));

	/** 도커 이미지 이름. 태그는 운영의 버전이다. */
	public final String image;
	/** 컨테이너 안의 포트. */
	public final int port;
	/** 컨테이너에 관리자 암호를 넘기는 환경 변수. */
	public final String passwordVariable;
	private final String versionQuery;
	private final String parametersQuery;
	private final List<Pattern> excluded;

	Engine(String image, int port, String passwordVariable, String versionQuery, String parametersQuery, List<String> excluded) {
		this.image = image;
		this.port = port;
		this.passwordVariable = passwordVariable;
		this.versionQuery = versionQuery;
		this.parametersQuery = parametersQuery;
		this.excluded = excluded.stream().map(Pattern::compile).toList();
	}

	public static Engine of(boolean postgres) {
		return postgres ? POSTGRES : MYSQL;
	}

	/**
	 * 서버가 알려 준 버전에서 이미지의 태그를 뽑는다. {@code 8.4.11-log} → {@code 8.4.11},
	 * {@code 16.4 (Debian 16.4-1.pgdg120+2)} → {@code 16.4}.
	 */
	public static String tag(String version) {
		String head = version.strip().split("[\\s-]", 2)[0];
		if (!head.matches("\\d+(\\.\\d+)*")) {
			throw new IllegalArgumentException("버전을 읽을 수 없다: " + version);
		}
		return head;
	}

	public String version(Connection connection) throws SQLException {
		try (Statement st = connection.createStatement(); ResultSet rows = st.executeQuery(versionQuery)) {
			rows.next();
			return rows.getString(1);
		}
	}

	/** 옮길 수 있는 파라미터. 장비와 경로, 서버의 정체성에 매인 것은 뺀다. */
	public Map<String, String> parameters(Connection connection) throws SQLException {
		Map<String, String> values = new TreeMap<>();
		try (Statement st = connection.createStatement(); ResultSet rows = st.executeQuery(parametersQuery)) {
			while (rows.next()) {
				String name = rows.getString(1).toLowerCase(java.util.Locale.ROOT);
				if (!excluded(name)) {
					values.put(name, rows.getString(2) == null ? "" : rows.getString(2));
				}
			}
		}
		return values;
	}

	public boolean excluded(String name) {
		return excluded.stream().anyMatch(p -> p.matcher(name).matches());
	}

	/** 다음에 서버가 뜰 때 이 값으로 뜨게 적는 문장. 시작할 때만 정할 수 있는 값도 여기에 적는다. */
	public String persist(String name, String value) {
		if (!name.matches("[a-z0-9_.]+")) {
			throw new IllegalArgumentException("파라미터 이름이 이상하다: " + name);
		}
		return switch (this) {
			case MYSQL -> "SET PERSIST_ONLY " + name + " = " + mysqlLiteral(value);
			case POSTGRES -> "ALTER SYSTEM SET " + name + " = '" + value.replace("'", "''") + "'";
		};
	}

	/** 숫자와 ON/OFF는 그대로, 나머지는 따옴표로 감싼다. 정수 변수에 문자열을 주면 MySQL이 거절한다. */
	static String mysqlLiteral(String value) {
		if (value.matches("-?\\d+(\\.\\d+)?") || value.equalsIgnoreCase("ON") || value.equalsIgnoreCase("OFF")) {
			return value;
		}
		return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
	}

	/** 두 쪽의 파라미터 가운데 값이 다르거나 한쪽에만 있는 것. 이름 순서다. */
	public static Map<String, String[]> diff(Map<String, String> like, Map<String, String> sandbox) {
		Map<String, String[]> diff = new TreeMap<>();
		like.forEach((name, value) -> {
			String other = sandbox.get(name);
			if (!value.equals(other)) {
				diff.put(name, new String[] {value, other});
			}
		});
		sandbox.forEach((name, value) -> {
			if (!like.containsKey(name)) {
				diff.put(name, new String[] {null, value});
			}
		});
		return diff;
	}
}
