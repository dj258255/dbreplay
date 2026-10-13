package io.dbreplay.sandbox;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.dbreplay.replay.JdbcTarget;
import io.dbreplay.trace.PrivateFile;

/**
 * 녹화를 시작하기 직전의 데이터 스냅샷을 뜬다. 메이투안의 시스템은 녹화 시작 시점의 전체 데이터를 스냅샷으로
 * 떠서 샌드박스를 만든다. 이 도구는 그 스냅샷을 {@link Sandbox}로 되부을 수 있기만 하면 되므로, 기종의
 * 논리 덤프 도구(mysqldump, pg_dump)를 불러 평문 덤프를 뜬다.
 *
 * <p>실험 11에서 GTID가 켜진 MySQL의 덤프에 {@code SET @@GLOBAL.GTID_PURGED}가 들어가 되부을 때 걸리는 것을
 * 뒤늦게 알았다. 그래서 오라클의 mysqldump에는 {@code --set-gtid-purged=OFF}를 준다. MariaDB의
 * mysqldump는 이 옵션을 모르고 그 문장도 쓰지 않으므로 붙이지 않는다.
 *
 * <p>실험 16에서 실행 환경의 pg_dump가 서버(16)보다 새 버전이라 되붓기가 실패하는 것을 겪었다. 새 pg_dump는
 * 덤프의 머리에 {@code SET transaction_timeout = 0;}을 넣는데 16은 그 설정을 모른다. {@code reset}은
 * {@code ON_ERROR_STOP=1}로 되부어서 여기서 끝난다. pg_dump에는 서버 버전에 맞춰 쓰라는 옵션이 없어서,
 * 서버가 아는 설정 이름({@code pg_settings})을 읽어 덤프의 머리에서 모르는 설정 줄만 뺀다. 머리에서만 거르는
 * 이유는 테이블의 데이터 안에 같은 글자가 한 줄로 들어 있을 수 있기 때문이다.
 */
public final class Snapshot {

	/** {@code SET 이름 = 값;} 꼴. {@code SET SESSION AUTHORIZATION ...}처럼 이름 다음이 =가 아니면 걸리지 않는다. */
	private static final Pattern SETTING = Pattern.compile("SET\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=.*");

	private final JdbcTarget target;
	private final String database;
	private final List<String> dropped = new ArrayList<>();

	/** @param database 덤프를 뜰 데이터베이스. null이면 URL의 것 */
	public Snapshot(JdbcTarget target, String database) {
		this.target = target;
		this.database = database;
	}

	/** 마지막 {@link #dump}에서 덤프의 머리에서 뺀 줄들. MySQL이면 빈 리스트. */
	public List<String> droppedSettings() {
		return List.copyOf(dropped);
	}

	/**
	 * 덤프를 {@code out}에 쓰고 파일의 바이트 수를 돌려준다.
	 *
	 * <p>암호는 명령의 인자로 넘기지 않는다. 프로세스 목록에 남기 때문이다. MySQL은 {@code MYSQL_PWD},
	 * PostgreSQL은 {@code PGPASSWORD} 환경 변수로 준다.
	 *
	 * @throws IllegalArgumentException 데이터베이스를 URL에서도 인자에서도 알 수 없을 때
	 */
	public long dump(Path out) throws IOException, InterruptedException, SQLException {
		JdbcTarget.Endpoint endpoint = target.endpoint();
		String db = database != null ? database : endpoint.database();
		if (db == null) {
			throw new IllegalArgumentException("덤프를 뜰 데이터베이스를 모른다. URL에 넣거나 따로 지정해야 한다");
		}
		boolean postgres = target.isPostgres();
		// MariaDB의 mysqldump인지는 --version 출력의 글자로 가른다. 명령을 만들기 전에 본다
		boolean mariadbClient = !postgres && isMariaDbClient(clientVersion());
		dropped.clear();
		// PostgreSQL만 설정을 거른다. 서버가 아는 이름을 미리 읽어 둔다
		Set<String> knownSettings = postgres ? knownSettings(db) : Set.of();
		ProcessBuilder builder = new ProcessBuilder(command(postgres, mariadbClient, endpoint, target.user(), db));
		builder.environment().putAll(environment(postgres, target.password()));
		if (postgres) {
			dumpPostgres(builder, out, knownSettings);
		}
		else {
			dumpMysql(builder, out);
		}
		return Files.size(out);
	}

	/** PostgreSQL은 표준 출력을 읽어 머리의 모르는 설정을 걸러 쓴다. */
	private void dumpPostgres(ProcessBuilder builder, Path out, Set<String> knownSettings)
			throws IOException, InterruptedException {
		// 표준 오류를 파일로 받는다. 표준 출력을 읽는 동안 오류 파이프가 차면 프로세스가 멈추기 때문이다
		Path errorFile = Files.createTempFile("dbreplay-pg-dump-", ".err");
		try {
			builder.redirectError(errorFile.toFile());
			Process process = builder.start();
			// 덤프는 데이터베이스의 인코딩으로 나온다. ISO_8859_1은 바이트를 글자 하나로 그대로 옮기는 인코딩이라
			// 어떤 인코딩의 덤프든 바이트가 바뀌지 않는다. UTF-8로 읽으면 그 인코딩이 아닌 글자가 깨진다.
			// 만든 핸들에 바로 쓴다. 경로로 다시 열면 그 사이에 경로가 바뀔 수 있다
			try (BufferedReader in = new BufferedReader(
						new InputStreamReader(process.getInputStream(), StandardCharsets.ISO_8859_1));
					Writer writer = new BufferedWriter(
							new OutputStreamWriter(PrivateFile.open(out), StandardCharsets.ISO_8859_1))) {
				dropped.addAll(copyDroppingUnknownSettings(in, writer, knownSettings));
			}
			int exit = process.waitFor();
			if (exit != 0) {
				// 쓰다 만 파일을 남기지 않는다. reset이 그것을 되부으면 반쪽짜리 데이터가 된다
				Files.deleteIfExists(out);
				throw new IOException("pg_dump이(가) 실패했다 (종료 코드 " + exit + "): " + errorTail(errorFile));
			}
		}
		finally {
			Files.deleteIfExists(errorFile);
		}
	}

	/**
	 * MySQL은 표준 출력을 읽어 만든 핸들에 그대로 옮긴다. {@code redirectOutput}은 경로로 다시 여는 것이라,
	 * 그 사이에 경로가 심볼릭 링크로 바뀌면 덤프가 다른 파일로 갈 수 있다.
	 */
	private void dumpMysql(ProcessBuilder builder, Path out) throws IOException, InterruptedException {
		// 표준 출력을 읽는 동안 표준 오류가 차면 프로세스가 멈춘다. 임시 파일로 받는다
		Path errorFile = Files.createTempFile("dbreplay-mysql-dump-", ".err");
		try {
			builder.redirectError(errorFile.toFile());
			Process process = builder.start();
			try (OutputStream sink = PrivateFile.open(out)) {
				process.getInputStream().transferTo(sink);
			}
			int exit = process.waitFor();
			if (exit != 0) {
				Files.deleteIfExists(out);
				throw new IOException("mysqldump이(가) 실패했다 (종료 코드 " + exit + "): " + errorTail(errorFile));
			}
		}
		finally {
			Files.deleteIfExists(errorFile);
		}
	}

	/**
	 * 덤프의 머리에서 서버가 모르는 설정 줄을 빼고 나머지를 {@code out}에 쓴다.
	 *
	 * <p>머리는 파일의 처음부터 아래가 아닌 첫 줄이 나오기 전까지다: 빈 줄, {@code --}로 시작하는 주석,
	 * {@code SET }으로 시작하는 줄, {@code SELECT pg_catalog.set_config}로 시작하는 줄, {@code \}로 시작하는
	 * psql 메타 명령. 머리가 끝난 뒤에는 한 줄도 건드리지 않는다. 테이블의 데이터 안에 {@code SET} 글자가
	 * 한 줄로 들어 있을 수 있다.
	 *
	 * @return 뺀 줄들
	 */
	static List<String> copyDroppingUnknownSettings(BufferedReader in, Writer out, Set<String> knownSettings)
			throws IOException {
		List<String> dropped = new ArrayList<>();
		boolean inHeader = true;
		for (String line; (line = in.readLine()) != null;) {
			if (inHeader) {
				if (isHeaderLine(line)) {
					if (isUnknownSetting(line, knownSettings)) {
						dropped.add(line);
						continue;
					}
				}
				else {
					inHeader = false;
				}
			}
			out.write(line);
			out.write('\n');
		}
		return dropped;
	}

	/** {@code SET 이름 = 값;} 줄에서 설정 이름만 뽑는다. {@link #droppedSettings() 뺀 줄}을 사람에게 보여 줄 때 쓴다. */
	public static String settingName(String line) {
		Matcher m = SETTING.matcher(line);
		return m.matches() ? m.group(1) : line;
	}

	private static boolean isHeaderLine(String line) {
		return line.isBlank() || line.startsWith("--") || line.startsWith("SET ")
				|| line.startsWith("SELECT pg_catalog.set_config") || line.startsWith("\\");
	}

	private static boolean isUnknownSetting(String line, Set<String> knownSettings) {
		Matcher m = SETTING.matcher(line);
		return m.matches() && !knownSettings.contains(m.group(1).toLowerCase(Locale.ROOT));
	}

	/** 서버가 아는 설정 이름. 소문자로 담는다. pg_dump는 이름을 소문자로 쓰지만 서버가 준 것도 맞춰 둔다. */
	private Set<String> knownSettings(String db) throws SQLException {
		Set<String> names = new HashSet<>();
		try (Connection connection = target.connect(db);
				Statement statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("SELECT name FROM pg_settings")) {
			while (rows.next()) {
				names.add(rows.getString(1).toLowerCase(Locale.ROOT));
			}
		}
		return names;
	}

	/** 이 스냅샷이 부를 명령. 암호가 인자에 없다는 것을 테스트할 수 있게 둔다. */
	List<String> command(boolean mariadbClient) {
		JdbcTarget.Endpoint endpoint = target.endpoint();
		String db = database != null ? database : endpoint.database();
		return command(target.isPostgres(), mariadbClient, endpoint, target.user(), db);
	}

	/**
	 * 덤프 도구를 부르는 명령.
	 *
	 * <p>PostgreSQL에는 {@code --clean --if-exists}를 준다. 없으면 {@code reset}이 같은 데이터베이스에
	 * 다시 부을 때 이미 있는 객체에 걸린다(실험 04가 이 옵션으로 덤프한다).
	 *
	 * <p>MySQL의 {@code --single-transaction}은 덤프하는 동안 쓰기를 막지 않고, {@code --no-tablespaces}는
	 * 없으면 PROCESS 권한을 요구한다. {@code --set-gtid-purged=OFF}의 이유는 클래스 주석에 있다.
	 *
	 * @param mariadbClient MariaDB의 mysqldump면 true. {@code --set-gtid-purged=OFF}를 모른다
	 */
	static List<String> command(boolean postgres, boolean mariadbClient, JdbcTarget.Endpoint endpoint, String user,
			String database) {
		if (postgres) {
			return List.of("pg_dump", "-h", endpoint.host(), "-p", Integer.toString(endpoint.port()), "-U", user, "-d",
					database, "--clean", "--if-exists");
		}
		List<String> tokens = new ArrayList<>(List.of("mysqldump", "--host=" + endpoint.host(),
				"--port=" + endpoint.port(), "--user=" + user, "--single-transaction", "--no-tablespaces"));
		if (!mariadbClient) {
			tokens.add("--set-gtid-purged=OFF");
		}
		tokens.add(database);
		return tokens;
	}

	/** mysqldump --version의 출력에 MariaDB가 있는지로 가른다. 오라클 클라이언트는 이 말을 쓰지 않는다. */
	static boolean isMariaDbClient(String versionOutput) {
		return versionOutput.toLowerCase(Locale.ROOT).contains("mariadb");
	}

	/**
	 * 덤프 도구에 암호를 넘기는 환경 변수. 암호를 인자로 넘기면 프로세스 목록에 남는다. PostgreSQL은
	 * PGPASSWORD, MySQL은 MYSQL_PWD를 읽는다.
	 *
	 * <p>암호가 null이면 빈 맵이다. 환경 변수에 null을 넣으면 예외가 난다.
	 */
	static Map<String, String> environment(boolean postgres, String password) {
		if (password == null) {
			return Map.of();
		}
		return Map.of(postgres ? "PGPASSWORD" : "MYSQL_PWD", password);
	}

	private static String errorTail(Path file) throws IOException {
		return tail(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	private static String tail(byte[] output, java.nio.charset.Charset charset) {
		String text = new String(output, charset).strip();
		return text.length() > 500 ? text.substring(text.length() - 500) : text;
	}

	private String clientVersion() throws IOException, InterruptedException {
		ProcessBuilder builder = new ProcessBuilder("mysqldump", "--version");
		builder.redirectErrorStream(true);
		Process process = builder.start();
		byte[] output = process.getInputStream().readAllBytes();
		process.waitFor();
		return new String(output, StandardCharsets.UTF_8);
	}
}