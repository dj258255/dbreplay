package io.dbreplay.sandbox;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import io.dbreplay.replay.JdbcTarget;

/**
 * 샌드박스를 녹화 시작 시점으로 되돌린다. 메이투안의 시스템이 라운드마다 자동으로 하는 일이다.
 *
 * <p>순서는 덤프 되붓기, 변경 넣기, 통계 다시 잡기, 조용해질 때까지 기다리기다. 뒤의 둘은 실험에서 틀렸던
 * 자리다. 되돌린 직후에는 통계가 적재 도중의 값으로 남아 실행 계획이 바뀌었고(실험 02), 서버가 되부은 쓰기를
 * 디스크에 내리는 동안에는 COMMIT이 느렸다(실험 03).
 *
 * <p>덤프를 되붓는 데는 mysql과 psql 클라이언트를 쓴다. 논리 덤프는 어느 환경에서나 되지만 데이터가 크면
 * 라운드마다 오래 걸린다.
 */
public final class Sandbox {

	/**
	 * @param snapshotFile  녹화 시작 시점의 논리 덤프
	 * @param database      덤프를 부을 데이터베이스. null이면 URL의 것
	 * @param changeCommand 되부은 뒤 통계를 잡기 전에 실행할 명령. 글의 "변경 창"이다. 없으면 null
	 * @param readyCommand  다 끝난 뒤 재생 직전에 실행할 명령. 없으면 null
	 * @param settleSeconds 조용해지기를 기다리는 시간의 상한. 0이면 기다리지 않는다
	 */
	public record Options(String snapshotFile, String database, String changeCommand, String readyCommand, long settleSeconds) {
	}

	/** @param settled 시간 안에 조용해졌으면 true. 기다리지 않았으면 false */
	public record Timing(long restoreMillis, long changeMillis, long analyzeMillis, long settleMillis, boolean settled) {

		public long totalMillis() {
			return restoreMillis + changeMillis + analyzeMillis + settleMillis;
		}
	}

	private static final String MYSQL_DIRTY_PAGES =
			"SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Innodb_buffer_pool_pages_dirty'";

	private final JdbcTarget target;
	private final Options options;

	public Sandbox(JdbcTarget target, Options options) {
		this.target = target;
		this.options = options;
	}

	public Timing reset() throws IOException, InterruptedException, SQLException {
		JdbcTarget.Endpoint endpoint = target.endpoint();
		String database = options.database() != null ? options.database() : endpoint.database();
		if (database == null) {
			throw new IllegalArgumentException("덤프를 부을 데이터베이스를 모른다. URL에 넣거나 따로 지정해야 한다");
		}

		long t0 = System.nanoTime();
		restore(endpoint, database);
		long t1 = System.nanoTime();
		if (options.changeCommand() != null) {
			run(new ProcessBuilder("sh", "-c", options.changeCommand()), null, "변경 명령");
		}
		long t2 = System.nanoTime();
		boolean settled = false;
		long t3;
		long t4;
		try (Connection connection = target.connect(database); Statement statement = connection.createStatement()) {
			analyze(statement, database);
			t3 = System.nanoTime();
			if (options.settleSeconds() > 0) {
				settled = settle(statement);
			}
			t4 = System.nanoTime();
		}
		if (options.readyCommand() != null) {
			run(new ProcessBuilder("sh", "-c", options.readyCommand()), null, "준비 명령");
		}
		return new Timing((t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, (t4 - t3) / 1_000_000, settled);
	}

	private void restore(JdbcTarget.Endpoint endpoint, String database) throws IOException, InterruptedException {
		ProcessBuilder builder;
		if (target.isPostgres()) {
			builder = new ProcessBuilder("psql", "-h", endpoint.host(), "-p", Integer.toString(endpoint.port()),
					"-U", target.user(), "-d", database, "-q", "-v", "ON_ERROR_STOP=1", "-f", options.snapshotFile());
			builder.environment().put("PGPASSWORD", target.password());
			run(builder, null, "psql");
		}
		else {
			builder = new ProcessBuilder("mysql", "--host=" + endpoint.host(), "--port=" + endpoint.port(),
					"--user=" + target.user(), database);
			builder.environment().put("MYSQL_PWD", target.password());
			run(builder, new File(options.snapshotFile()), "mysql");
		}
	}

	/** 되부은 직후의 통계는 적재 도중의 값일 수 있다. 실험 02에서 10만 행 테이블의 행 수가 46으로 남았다. */
	private void analyze(Statement statement, String database) throws SQLException {
		if (target.isPostgres()) {
			// 적재 직후의 첫 조회는 행마다 힌트 비트를 쓰느라 느리다. VACUUM이 그것을 먼저 치른다
			statement.execute("VACUUM ANALYZE");
			return;
		}
		List<String> tables = new ArrayList<>();
		try (ResultSet rows = statement.executeQuery("SELECT TABLE_NAME FROM information_schema.TABLES "
				+ "WHERE TABLE_SCHEMA = '" + database.replace("'", "''") + "' AND TABLE_TYPE = 'BASE TABLE'")) {
			while (rows.next()) {
				tables.add("`" + database.replace("`", "``") + "`.`" + rows.getString(1).replace("`", "``") + "`");
			}
		}
		if (!tables.isEmpty()) {
			statement.execute("ANALYZE TABLE " + String.join(", ", tables));
		}
	}

	private boolean settle(Statement statement) throws SQLException, InterruptedException {
		if (target.isPostgres()) {
			// 기다리지 않는다. 처음에는 여기서 CHECKPOINT를 일으켰는데 그러면 재생이 체크포인트 직후의 조건에서 돈다.
			// 실험 05에서 그 차이를 쟀다
			return false;
		}
		// InnoDB는 한가할 때 더티 페이지를 계속 내린다. 그 수가 더 줄지 않으면 내릴 것을 다 내린 것이다
		return Settle.untilStable(() -> {
			try (ResultSet rows = statement.executeQuery(MYSQL_DIRTY_PAGES)) {
				rows.next();
				return rows.getLong(1);
			}
			catch (SQLException e) {
				throw new IllegalStateException(e);
			}
		}, 500, options.settleSeconds() * 1_000, 3);
	}

	private static void run(ProcessBuilder builder, File stdin, String what) throws IOException, InterruptedException {
		builder.redirectErrorStream(true);
		if (stdin != null) {
			builder.redirectInput(stdin);
		}
		Process process = builder.start();
		// 출력은 끝의 일부만 남긴다. 실패했을 때 이유를 보여 주려는 것이다
		byte[] output = process.getInputStream().readAllBytes();
		int exit = process.waitFor();
		if (exit != 0) {
			String tail = new String(output, java.nio.charset.StandardCharsets.UTF_8).strip();
			tail = tail.length() > 500 ? tail.substring(tail.length() - 500) : tail;
			throw new IOException(what + "이(가) 실패했다 (종료 코드 " + exit + "): " + tail);
		}
	}
}
