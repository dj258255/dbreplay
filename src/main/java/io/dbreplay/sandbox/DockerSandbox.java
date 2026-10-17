package io.dbreplay.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.dbreplay.replay.JdbcTarget;

/**
 * 운영과 같은 기종, 같은 버전의 샌드박스를 도커로 띄우고 운영의 파라미터를 옮긴다(원문 대조 20c, 37, #84). 글은 샌드박스를 만들고
 * 기종, 버전, 파라미터를 운영과 같게 맞춘다. 클라우드 API로 인스턴스를 띄우는 것은 하지 않는 범위다(ROADMAP).
 *
 * <p>파라미터는 영구 설정으로 적고(MySQL {@code SET PERSIST_ONLY}, PostgreSQL {@code ALTER SYSTEM}) 컨테이너를 한 번 다시 띄운다.
 * 시작할 때만 정할 수 있는 값도 그렇게 옮겨진다. 옮긴 뒤 다시 비교해 여전히 다른 것만 "옮기지 못한 것"으로 알린다.
 * 관리자 암호는 명령줄이 아니라 환경 변수로 컨테이너에 넘긴다.
 */
public final class DockerSandbox {

	/** 띄운 결과. */
	public record Created(JdbcTarget target, String image, int copied, Map<String, String[]> notCopied) {
	}

	private static final Pattern URL = Pattern.compile("^(jdbc:[a-z]+://)([^/?]+)(/[^?]*)?(\\?.*)?$");
	private static final long WAIT_MILLIS = 180_000;

	private DockerSandbox() {
	}

	/**
	 * @param like      운영(따라 할 쪽)
	 * @param name      컨테이너 이름
	 * @param hostPort  샌드박스를 열 호스트의 포트
	 * @param password  샌드박스의 관리자 암호
	 * @param image     이미지를 정해 주면 그것을 쓴다. null이면 운영의 버전으로 고른다
	 */
	public static Created create(JdbcTarget like, String name, int hostPort, String password, String image)
			throws IOException, InterruptedException, SQLException {
		Engine engine = Engine.of(like.isPostgres());
		String version;
		Map<String, String> wanted;
		try (Connection c = like.connect(null)) {
			version = engine.version(c);
			wanted = engine.parameters(c);
		}
		String chosen = image != null ? image : engine.image + ":" + Engine.tag(version);
		docker(List.of("run", "-d", "--name", name, "-e", engine.passwordVariable, "-p", hostPort + ":" + engine.port, chosen),
				Map.of(engine.passwordVariable, password));
		// 갓 띄운 서버에는 운영의 데이터베이스가 없다. 관리 작업은 데이터베이스 없이(PostgreSQL은 postgres로) 붙는다
		JdbcTarget sandbox = new JdbcTarget(sandboxUrl(like.url(), hostPort, engine == Engine.POSTGRES ? "/postgres" : "/"),
				adminUser(engine), password);
		waitUntilUp(sandbox);

		int copied = 0;
		Map<String, String[]> before;
		try (Connection c = sandbox.connect(null)) {
			before = Engine.diff(wanted, engine.parameters(c));
			try (Statement st = c.createStatement()) {
				for (Map.Entry<String, String[]> d : before.entrySet()) {
					if (d.getValue()[0] == null) {
						continue; // 운영에 없는 파라미터(플러그인 등)는 건드리지 않는다
					}
					try {
						st.execute(engine.persist(d.getKey(), d.getValue()[0]));
						copied++;
					}
					catch (SQLException e) {
						// 읽기만 되는 값이나 이 버전이 받지 않는 값. 아래에서 다시 비교해 알린다
					}
				}
			}
		}
		docker(List.of("restart", name), Map.of());
		waitUntilUp(sandbox);
		Map<String, String[]> after;
		try (Connection c = sandbox.connect(null); Connection l = like.connect(null)) {
			after = Engine.diff(engine.parameters(l), engine.parameters(c));
		}
		String database = like.endpoint().database();
		if (database != null) {
			createDatabase(sandbox, engine, database);
		}
		return new Created(new JdbcTarget(sandboxUrl(like.url(), hostPort, null), adminUser(engine), password), chosen, copied,
				after);
	}

	/** 운영과 샌드박스의 버전과 파라미터 차이. 첫 줄은 버전이다(같으면 빈 값). */
	public static Map<String, String[]> diff(JdbcTarget like, JdbcTarget sandbox) throws SQLException {
		Engine engine = Engine.of(like.isPostgres());
		try (Connection l = like.connect(null); Connection s = sandbox.connect(null)) {
			Map<String, String[]> diff = new java.util.LinkedHashMap<>();
			String lv = engine.version(l), sv = engine.version(s);
			if (!Engine.tag(lv).equals(Engine.tag(sv))) {
				diff.put("(버전)", new String[] {lv, sv});
			}
			diff.putAll(Engine.diff(engine.parameters(l), engine.parameters(s)));
			return diff;
		}
	}

	public static void drop(String name) throws IOException, InterruptedException {
		docker(List.of("rm", "-f", "-v", name), Map.of());
	}

	/**
	 * 운영의 URL에서 호스트와 포트를 샌드박스의 것으로 바꾼다. 접속 옵션은 그대로 둔다.
	 *
	 * @param path 데이터베이스 자리를 바꿀 때 준다({@code /}, {@code /postgres}). null이면 운영의 것을 그대로 둔다
	 */
	public static String sandboxUrl(String likeUrl, int hostPort, String path) {
		Matcher m = URL.matcher(likeUrl);
		if (!m.matches()) {
			throw new IllegalArgumentException("JDBC URL을 읽을 수 없다");
		}
		String keptPath = path != null ? path : m.group(3) == null ? "" : m.group(3);
		return m.group(1) + "127.0.0.1:" + hostPort + keptPath + (m.group(4) == null ? "" : m.group(4));
	}

	/** 운영의 데이터베이스를 빈 채로 만든다. 덤프를 되부을 자리다. 이름은 따옴표로 감싸 넣는다. */
	private static void createDatabase(JdbcTarget admin, Engine engine, String database) throws SQLException {
		try (Connection c = admin.connect(null); Statement st = c.createStatement()) {
			if (engine == Engine.MYSQL) {
				st.execute("CREATE DATABASE IF NOT EXISTS `" + database.replace("`", "``") + "`");
				return;
			}
			try (var rows = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + database.replace("'", "''") + "'")) {
				if (rows.next()) {
					return;
				}
			}
			st.execute("CREATE DATABASE \"" + database.replace("\"", "\"\"") + "\"");
		}
	}

	static String adminUser(Engine engine) {
		return engine == Engine.POSTGRES ? "postgres" : "root";
	}

	private static void waitUntilUp(JdbcTarget target) throws InterruptedException, SQLException {
		long deadline = System.currentTimeMillis() + WAIT_MILLIS;
		while (true) {
			try (Connection c = target.connect(null)) {
				return;
			}
			catch (SQLException e) {
				if (System.currentTimeMillis() > deadline) {
					throw new SQLException("샌드박스가 " + WAIT_MILLIS / 1000 + "초 안에 뜨지 않았다", e.getSQLState());
				}
				Thread.sleep(2_000);
			}
		}
	}

	private static void docker(List<String> args, Map<String, String> env) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add("docker");
		command.addAll(args);
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.environment().putAll(env);
		Process p = builder.start();
		String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
		int exit = p.waitFor();
		if (exit != 0) {
			// 도커의 오류에는 암호가 들지 않는다(환경 변수 이름만 넘겼다)
			throw new IOException("docker " + args.getFirst() + "가 실패했다 (종료 코드 " + exit + "): " + output);
		}
	}
}
