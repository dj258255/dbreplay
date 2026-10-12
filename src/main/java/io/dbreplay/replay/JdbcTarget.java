package io.dbreplay.replay;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/** JDBC로 붙는 대상. 기종은 URL과 클래스패스의 드라이버가 정한다. */
public final class JdbcTarget implements Target {

	private final String url;
	private final String user;
	private final String password;

	public JdbcTarget(String url, String user, String password) {
		this.url = url;
		this.user = user;
		this.password = password;
	}

	/** JDBC URL에서 읽은 접속 위치. 클라이언트 도구를 부를 때 쓴다. database는 URL에 없으면 null. */
	public record Endpoint(String host, int port, String database) {
	}

	private static final java.util.regex.Pattern ENDPOINT =
			java.util.regex.Pattern.compile("jdbc:(mysql|postgresql)://([^:/?]+)(?::(\\d+))?(?:/([^?]*))?.*");

	public Endpoint endpoint() {
		java.util.regex.Matcher m = ENDPOINT.matcher(url);
		if (!m.matches()) {
			// URL의 ? 뒤에는 암호가 들어 있을 수 있다. 메시지에 싣지 않는다
			int query = url.indexOf('?');
			throw new IllegalArgumentException("접속 위치를 읽을 수 없는 URL이다: " + (query < 0 ? url : url.substring(0, query)));
		}
		int port = m.group(3) != null ? Integer.parseInt(m.group(3)) : m.group(1).equals("mysql") ? 3306 : 5432;
		String database = m.group(4) == null || m.group(4).isEmpty() ? null : m.group(4);
		return new Endpoint(m.group(2), port, database);
	}

	public String user() {
		return user;
	}

	public String password() {
		return password;
	}

	public boolean isPostgres() {
		return url.startsWith("jdbc:postgresql:");
	}

	@Override
	public TargetSession open(String db) throws SQLException {
		return new Session(connect(db), !isPostgres());
	}

	/** 재생 말고 대상을 들여다보는 용도의 연결. 부르는 쪽이 닫는다. */
	public Connection connect(String db) throws SQLException {
		boolean postgres = isPostgres();
		String target = postgres && db != null ? withDatabase(url, db) : url;
		Properties properties = new Properties();
		properties.setProperty("user", user);
		properties.setProperty("password", password);
		if (url.startsWith("jdbc:mysql:") && !url.contains("useLocalSessionState")) {
			// Connector/J는 쓰기 문장마다 SELECT @@session.transaction_read_only를 먼저 보낸다. 원본에 없던 왕복이라 끈다.
			// 실험 03에서 문장 118,822개를 트는 동안 이 조회가 23,764번 더 나갔다.
			properties.setProperty("useLocalSessionState", "true");
		}
		return DriverManager.getConnection(target, properties);
	}

	/**
	 * PostgreSQL은 연결이 데이터베이스 하나에 묶인다. URL의 데이터베이스 자리를 바꿔 끼운다.
	 * {@code jdbc:postgresql://host:5432/postgres?x=y}에 shop을 주면 {@code .../shop?x=y}가 된다.
	 */
	static String withDatabase(String url, String db) {
		int query = url.indexOf('?');
		String params = query >= 0 ? url.substring(query) : "";
		String base = query >= 0 ? url.substring(0, query) : url;
		int slash = base.indexOf('/', base.indexOf("//") + 2);
		return (slash >= 0 ? base.substring(0, slash) : base) + "/" + db + params;
	}

	private static final class Session implements TargetSession {

		private final Connection connection;
		// MySQL은 연결을 둔 채 기본 데이터베이스를 바꿀 수 있다
		private final boolean switchesDatabase;
		private String currentDb;

		Session(Connection connection, boolean switchesDatabase) {
			this.connection = connection;
			this.switchesDatabase = switchesDatabase;
		}

		@Override
		public int execute(String db, String sql) {
			try {
				if (switchesDatabase && db != null && !db.equals(currentDb)) {
					connection.setCatalog(db);
					currentDb = db;
				}
				try (Statement statement = connection.createStatement()) {
					boolean hasRows = statement.execute(sql);
					while (hasRows || statement.getUpdateCount() != -1) {
						if (hasRows) {
							// 원본의 클라이언트도 행을 다 받았다. 받지 않으면 대상이 보내는 비용이 빠진다
							try (ResultSet rows = statement.getResultSet()) {
								while (rows.next()) {
								}
							}
						}
						hasRows = statement.getMoreResults();
					}
				}
				return 0;
			}
			catch (SQLException e) {
				return e.getErrorCode() != 0 ? e.getErrorCode() : UNKNOWN_ERROR;
			}
		}

		@Override
		public void close() {
			try {
				connection.close();
			}
			catch (SQLException ignored) {
				// 닫는 중의 오류는 재생 결과에 영향이 없다
			}
		}
	}
}
