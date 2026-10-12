import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 변환한 트래픽 파일을 한 줄씩 읽어 순서를 검사한다. 파일을 메모리에 올리지 않는다.
 *
 * <p>1) 전체가 시작 시각 순인가. 2) 한 연결 안의 순서가 로그와 같은가. 만들 때 단 순번이 0부터 하나씩 늘고,
 * BEGIN, 문장들, COMMIT의 차례가 지켜지는지로 본다. 3) 끊긴 연결이 다시 나오지 않는가.
 *
 * <p>사용: java CheckTrace.java <트래픽 파일>. 어긋난 것이 있으면 1로 끝난다.
 */
public class CheckTrace {

	static final class Session {
		long nextSeq;
		boolean inTransaction;
		int statementsInTransaction;
	}

	public static void main(String[] args) throws IOException {
		Map<String, Session> open = new HashMap<>();
		Set<String> closed = new HashSet<>();
		long lines = 0, statements = 0, closes = 0, tagged = 0, sessions = 0, violations = 0;
		long lastStart = Long.MIN_VALUE;

		try (BufferedReader in = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
			in.readLine(); // 머리말
			for (String line; (line = in.readLine()) != null;) {
				lines++;
				String session = text(line, "\"session\":\"");
				long start = Long.parseLong(number(line, "\"startMicros\":"));
				if (start < lastStart) {
					violations += report("시작 시각이 거꾸로 갔다", lines, line);
				}
				lastStart = start;
				if (closed.contains(session)) {
					violations += report("끊긴 연결이 다시 나왔다", lines, line);
				}
				Session s = open.get(session);
				if (s == null) {
					s = new Session();
					open.put(session, s);
					sessions++;
				}
				if (line.endsWith("\"close\":true}")) {
					closes++;
					if (s.inTransaction) {
						violations += report("트랜잭션 안에서 연결이 끊겼다", lines, line);
					}
					open.remove(session);
					closed.add(session);
					continue;
				}
				statements++;
				String sql = text(line, "\"sql\":\"");
				if (sql.equals("BEGIN")) {
					if (s.inTransaction) {
						violations += report("BEGIN이 겹쳤다", lines, line);
					}
					s.inTransaction = true;
					s.statementsInTransaction = 0;
				}
				else if (sql.equals("COMMIT")) {
					if (!s.inTransaction || s.statementsInTransaction == 0) {
						violations += report("COMMIT의 자리가 틀렸다", lines, line);
					}
					s.inTransaction = false;
				}
				else {
					tagged++;
					int at = sql.indexOf("/* n=");
					long seq = Long.parseLong(sql.substring(at + 5, sql.indexOf(' ', at + 5)));
					if (seq != s.nextSeq || !s.inTransaction) {
						violations += report("연결 안의 순서가 로그와 다르다 (기대한 순번 " + s.nextSeq + ")", lines, line);
					}
					s.nextSeq = seq + 1;
					s.statementsInTransaction++;
				}
			}
		}
		System.out.printf("statements=%d%ncloses=%d%nsessions=%d%ntagged=%d%n", statements, closes, sessions, tagged);
		System.err.printf("줄 %d개를 검사했다. 어긋난 것 %d개%n", lines, violations);
		System.exit(violations == 0 ? 0 : 1);
	}

	static int report(String what, long lineNumber, String line) {
		System.err.println(what + " (줄 " + (lineNumber + 1) + "): " + line);
		return 1;
	}

	static String text(String line, String key) {
		int from = line.indexOf(key) + key.length();
		return line.substring(from, line.indexOf('"', from));
	}

	static String number(String line, String key) {
		int from = line.indexOf(key) + key.length();
		int to = from;
		while (Character.isDigit(line.charAt(to))) {
			to++;
		}
		return line.substring(from, to);
	}
}
