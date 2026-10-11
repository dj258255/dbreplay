package io.dbreplay.mysql;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL 8.x 슬로우 로그(파일 출력)를 항목 단위로 읽는다.
 *
 * <p>한 항목은 {@code # Time:} 줄에서 시작해 다음 {@code # Time:} 줄 앞에서 끝난다. 머리 줄 뒤의
 * {@code SET timestamp=...;} 다음부터가 본문이다. 본문 안에 {@code # Time:}으로 시작하는 줄이 있으면
 * 거기서 항목이 잘린다. 로그 형식만으로는 구분할 수 없다.
 */
public final class SlowLogParser {

	private static final String TIME = "# Time: ";
	private static final String USER_HOST = "# User@Host:";
	private static final String STATS = "# Query_time:";
	private static final String SET_TIMESTAMP = "SET timestamp=";
	private static final String ADMIN = "# administrator command: ";

	private static final Pattern CONNECTION_ID = Pattern.compile("\\bId:\\s*(\\d+)");
	private static final Pattern FIELD = Pattern.compile("(\\w+):\\s+(\\S+)");
	// 서버가 로그 파일을 (다시) 열 때 적는 세 줄짜리 머리말의 첫 줄
	private static final Pattern BANNER = Pattern.compile("^\\S*mysqld\\S*, Version: .* started with:$");

	private final BufferedReader in;
	private String lookahead;
	private long skipped;

	public SlowLogParser(Reader in) {
		this.in = in instanceof BufferedReader b ? b : new BufferedReader(in);
	}

	/** 머리 줄이나 본문이 없거나 값이 깨져 버린 항목 수. 파일이 중간에 잘렸을 때 생긴다. */
	public long skipped() {
		return skipped;
	}

	/** 다음 항목. 끝이면 null. */
	public SlowLogEntry next() throws IOException {
		while (true) {
			String line = lookahead != null ? lookahead : in.readLine();
			lookahead = null;
			while (line != null && !line.startsWith(TIME)) {
				line = in.readLine();
			}
			if (line == null) {
				return null;
			}
			try {
				SlowLogEntry entry = readEntry(parseMicros(line.substring(TIME.length()).trim()));
				if (entry != null) {
					return entry;
				}
			}
			catch (DateTimeException | ArithmeticException | IllegalArgumentException malformed) {
				// 숫자나 시각이 중간에 끊긴 항목. 다음 # Time 줄부터 다시 읽는다
			}
			skipped++;
		}
	}

	private SlowLogEntry readEntry(long endMicros) throws IOException {
		long connectionId = -1;
		long durationMicros = -1;
		long lockMicros = 0;
		long startMicros = -1;
		Integer errno = null;
		String useDb = null;
		boolean inBody = false;
		StringBuilder body = new StringBuilder();

		String line;
		while ((line = in.readLine()) != null) {
			if (line.startsWith(TIME)) {
				lookahead = line;
				break;
			}
			if (inBody) {
				if (BANNER.matcher(line).matches()) {
					in.readLine(); // Tcp port: ...
					in.readLine(); // Time  Id Command  Argument
					continue;
				}
				if (!body.isEmpty()) {
					body.append('\n');
				}
				body.append(line);
			}
			else if (line.startsWith(USER_HOST)) {
				Matcher m = CONNECTION_ID.matcher(line);
				if (m.find()) {
					connectionId = Long.parseLong(m.group(1));
				}
			}
			else if (line.startsWith(STATS)) {
				Matcher m = FIELD.matcher(line);
				while (m.find()) {
					switch (m.group(1)) {
						case "Query_time" -> durationMicros = secondsToMicros(m.group(2));
						case "Lock_time" -> lockMicros = secondsToMicros(m.group(2));
						case "Errno" -> errno = Integer.valueOf(m.group(2));
						case "Start" -> startMicros = parseMicros(m.group(2));
						default -> { }
					}
				}
			}
			else if (line.startsWith("use ") && line.endsWith(";")) {
				useDb = unquote(line.substring(4, line.length() - 1).trim());
			}
			else if (line.startsWith(SET_TIMESTAMP)) {
				inBody = true;
			}
		}

		if (connectionId < 0 || durationMicros < 0 || !inBody || body.isEmpty()) {
			return null;
		}
		if (startMicros < 0) {
			// log_slow_extra가 꺼져 있으면 Start가 없다. # Time은 끝난 시각이라 걸린 시간을 빼면 같다.
			startMicros = endMicros - durationMicros;
		}
		String text = body.toString().strip();
		if (text.startsWith(ADMIN)) {
			String command = stripSemicolon(text.substring(ADMIN.length()));
			return new SlowLogEntry(connectionId, startMicros, durationMicros, lockMicros, errno, useDb, command, null);
		}
		return new SlowLogEntry(connectionId, startMicros, durationMicros, lockMicros, errno, useDb, null, stripSemicolon(text));
	}

	private static String stripSemicolon(String s) {
		return s.endsWith(";") ? s.substring(0, s.length() - 1).stripTrailing() : s;
	}

	private static String unquote(String name) {
		return name.length() >= 2 && name.startsWith("`") && name.endsWith("`")
				? name.substring(1, name.length() - 1).replace("``", "`")
				: name;
	}

	private static long secondsToMicros(String seconds) {
		return new BigDecimal(seconds).movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact();
	}

	/** {@code 2026-10-11T01:28:01.469526Z}. log_timestamps=SYSTEM이면 Z 대신 +09:00 같은 오프셋이 온다. */
	private static long parseMicros(String timestamp) {
		Instant t = OffsetDateTime.parse(timestamp).toInstant();
		return t.getEpochSecond() * 1_000_000 + t.getNano() / 1_000;
	}
}
