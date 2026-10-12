import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * 변환기의 메모리를 시험할 큰 슬로우 로그를 만든다. 실제 서버의 로그가 아니라 형식만 같은 가짜다.
 *
 * <p>연결 64개가 각자 BEGIN, 문장 1~8개, COMMIT을 되풀이한다. 서버가 그러듯 문장이 끝난 순서로 적는다.
 * 드물게 수십 초짜리 문장이 있어서, 그 항목은 시작 순서로 있어야 할 자리보다 수백만 항목 뒤에 적힌다.
 * 경계가 아닌 문장에는 그 연결 안에서의 순번을 주석으로 단다. 검사기가 그 순번으로 순서를 확인한다.
 *
 * <p>사용: java GenerateSlowLog.java <출력 파일> <바이트 수> <기대값 파일>
 */
public class GenerateSlowLog {

	static final String STATS_TAIL = " Errno: 0 Killed: 0 Bytes_received: 39 Bytes_sent: 92 Read_first: 0 Read_last: 0"
			+ " Read_key: 1 Read_next: 0 Read_prev: 0 Read_rnd: 0 Read_rnd_next: 0 Sort_merge_passes: 0 Sort_range_count: 0"
			+ " Sort_rows: 0 Sort_scan_count: 0 Created_tmp_disk_tables: 0 Created_tmp_tables: 0";

	static final class Connection {
		long id;
		long seq;          // 이 연결의 다음 문장 순번
		int left = -1;     // 이 트랜잭션에 남은 문장 수. -1이면 BEGIN을 보낼 차례
		long transactions; // 이 연결이 끝낸 트랜잭션 수
		String sql;
		long start;
		long end;
		long writtenAtStart;
	}

	public static void main(String[] args) throws IOException {
		Path out = Path.of(args[0]);
		long targetBytes = Long.parseLong(args[1]);
		Random random = new Random(20261012);
		long base = LocalDateTime.of(2026, 10, 12, 0, 0).toEpochSecond(ZoneOffset.UTC) * 1_000_000L;
		long nextId = 100;
		long bytes = 0, entries = 0, tagged = 0, statements = 0, closes = 0, sessions = 0;
		long longest = 0, longestDisplacement = 0, longCount = 0, lastEnd = base;

		PriorityQueue<Connection> running = new PriorityQueue<>((a, b) -> Long.compare(a.end, b.end));
		for (int i = 0; i < 64; i++) {
			Connection c = new Connection();
			c.id = nextId++;
			sessions++;
			next(c, base + random.nextInt(1000), random, 0);
			running.add(c);
		}

		StringBuilder sb = new StringBuilder(1024);
		try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
			while (bytes < targetBytes) {
				Connection c = running.poll();
				sb.setLength(0);
				sb.append("# Time: ");
				time(sb, c.end);
				sb.append("\n# User@Host: app[app] @  [10.0.0.7]  Id: ").append(c.id);
				sb.append("\n# Query_time: ");
				seconds(sb, c.end - c.start);
				sb.append("  Lock_time: 0.000001 Rows_sent: 1  Rows_examined: 1");
				sb.append(" Thread_id: ").append(c.id).append(STATS_TAIL);
				sb.append(" Start: ");
				time(sb, c.start);
				sb.append(" End: ");
				time(sb, c.end);
				sb.append("\nSET timestamp=").append(c.start / 1_000_000L).append(";\n").append(c.sql).append(";\n");
				w.append(sb);
				bytes += sb.length();
				entries++;
				lastEnd = c.end;

				long duration = c.end - c.start;
				if (duration >= 1_000_000) {
					longCount++;
					longestDisplacement = Math.max(longestDisplacement, entries - c.writtenAtStart);
				}
				longest = Math.max(longest, duration);
				if (c.sql.startsWith("# administrator command: Quit")) {
					closes++;
					c = new Connection();
					c.id = nextId++;
					sessions++;
				}
				else {
					statements++;
					if (c.sql.contains("/* n=")) {
						tagged++;
					}
				}
				next(c, lastEnd + random.nextInt(400), random, entries);
				running.add(c);
			}
		}
		try (PrintWriter p = new PrintWriter(Files.newBufferedWriter(Path.of(args[2])))) {
			p.printf("statements=%d%ncloses=%d%nsessions=%d%ntagged=%d%n", statements, closes, sessions, tagged);
		}
		double seconds = (lastEnd - base) / 1e6;
		System.out.printf("항목 %d개, %.1fGB, 로그의 길이 %.0f초 (초당 %.0f항목)%n", entries, bytes / 1e9, seconds, entries / seconds);
		System.out.printf("1초 넘게 걸린 문장 %d개, 가장 긴 문장 %.1f초%n", longCount, longest / 1e6);
		System.out.printf("가장 멀리 밀린 항목: 시작 순서로 있어야 할 자리보다 %d항목 뒤에 적혔다%n", longestDisplacement);
	}

	/** 이 연결의 다음 문장을 정한다. 앞 문장이 끝난 뒤에 시작한다. */
	static void next(Connection c, long start, Random random, long entriesWritten) {
		c.start = start;
		c.writtenAtStart = entriesWritten;
		long duration = 20 + random.nextInt(600);
		if (c.left < 0) {
			// 트랜잭션 2,000개에 한 번쯤 연결을 끊고 새로 맺는다
			if (c.transactions > 0 && random.nextInt(2000) == 0) {
				c.sql = "# administrator command: Quit";
				c.transactions = 0;
				duration = 2;
			}
			else {
				c.sql = "BEGIN";
				c.left = 1 + random.nextInt(8);
			}
		}
		else if (c.left == 0) {
			c.sql = "COMMIT";
			c.left = -1;
			c.transactions++;
			duration = 200 + random.nextInt(2000);
		}
		else {
			long id = 1 + random.nextInt(1_000_000);
			c.sql = switch (random.nextInt(4)) {
				case 0 -> "UPDATE sbtest" + (1 + random.nextInt(4)) + " SET k=k+1 WHERE id=" + id;
				case 1 -> "SELECT c FROM sbtest" + (1 + random.nextInt(4)) + " WHERE id BETWEEN " + id + " AND " + (id + 99);
				default -> "SELECT c FROM sbtest" + (1 + random.nextInt(4)) + " WHERE id=" + id;
			} + " /* n=" + c.seq++ + " */";
			c.left--;
			// 50만 문장에 하나는 수십 초가 걸린다. 리포트 조회나 온라인 DDL을 흉내 낸다
			if (random.nextInt(500_000) == 0) {
				duration = 5_000_000L + (long) (random.nextDouble() * 85_000_000L);
			}
		}
		c.end = start + duration;
	}

	static void time(StringBuilder sb, long micros) {
		LocalDateTime t = LocalDateTime.ofEpochSecond(micros / 1_000_000L, 0, ZoneOffset.UTC);
		sb.append(t.getYear()).append('-');
		two(sb, t.getMonthValue()).append('-');
		two(sb, t.getDayOfMonth()).append('T');
		two(sb, t.getHour()).append(':');
		two(sb, t.getMinute()).append(':');
		two(sb, t.getSecond()).append('.');
		six(sb, micros % 1_000_000L).append('Z');
	}

	static void seconds(StringBuilder sb, long micros) {
		sb.append(micros / 1_000_000L).append('.');
		six(sb, micros % 1_000_000L);
	}

	static StringBuilder two(StringBuilder sb, int v) {
		return sb.append(v < 10 ? "0" : "").append(v);
	}

	static StringBuilder six(StringBuilder sb, long v) {
		String s = Long.toString(v);
		return sb.append("000000", 0, 6 - s.length()).append(s);
	}
}
