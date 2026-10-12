package io.dbreplay.postgres;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.dbreplay.convert.ConvertSummary;
import io.dbreplay.convert.HeadPolicy;
import io.dbreplay.convert.RecordBuffer;
import io.dbreplay.convert.SessionHead;
import io.dbreplay.convert.SqlWords;
import io.dbreplay.trace.TraceHeader;
import io.dbreplay.trace.TraceRecord;

/**
 * PostgreSQL의 csvlog를 트래픽 파일로 바꾼다.
 *
 * <p>녹화할 때의 설정은 {@code log_destination=csvlog}, {@code log_min_duration_statement=0},
 * {@code log_disconnections=on}, {@code log_timezone=UTC}다. 문장 줄은 끝났을 때 찍히고 시각이 밀리초
 * 단위라, 시작 시각은 찍힌 시각에서 걸린 시간을 빼서 구한다.
 *
 * <p>실패한 문장은 걸린 시간 없이 남는다. PostgreSQL에는 숫자로 된 오류 번호가 없어 실패를 1로 적는다.
 */
public final class CsvLogConverter {

	// csvlog의 칸 순서. 13판부터 앞의 23칸은 같고 뒤에 칸이 더해져 왔다
	private static final int LOG_TIME = 0;
	private static final int DATABASE = 2;
	private static final int SESSION_ID = 5;
	private static final int SEVERITY = 11;
	private static final int MESSAGE = 13;
	private static final int DETAIL = 14;
	private static final int QUERY = 19;
	private static final int MIN_COLUMNS = 20;

	/** PostgreSQL에는 오류 번호가 없다. 실패했다는 것만 적는다 */
	static final int FAILED = 1;

	private static final Pattern DURATION = Pattern.compile(
			"\\Aduration: ([0-9.]+) ms  (statement|execute|parse|bind)[^:]*: (.*)\\z", Pattern.DOTALL);
	private static final Pattern TIMESTAMP = Pattern.compile("(\\d{4}-\\d\\d-\\d\\d) (\\d\\d:\\d\\d:\\d\\d(?:\\.\\d+)?) (\\S+)");

	private static final class Session {
		final PgTransactionTracker tracker = new PgTransactionTracker();
		final SessionHead head = new SessionHead();
		long lastStartMicros = Long.MIN_VALUE;
	}

	private CsvLogConverter() {
	}

	public static ConvertSummary convert(Reader csvLog, Writer trace) throws IOException {
		return convert(csvLog, trace, HeadPolicy::byDefault);
	}

	public static ConvertSummary convert(Reader csvLog, Writer trace, HeadPolicy headPolicy) throws IOException {
		CsvReader reader = new CsvReader(csvLog);
		RecordBuffer buffer = new RecordBuffer();
		Map<String, Session> sessions = new HashMap<>();
		long entries = 0, ignored = 0, skipped = 0, clamped = 0;

		for (List<String> row; (row = reader.next()) != null;) {
			entries++;
			if (row.size() < MIN_COLUMNS || row.get(SESSION_ID) == null || row.get(MESSAGE) == null) {
				skipped++;
				continue;
			}
			String sessionId = row.get(SESSION_ID);
			String message = row.get(MESSAGE);
			long loggedMicros;
			String sql;
			long durationMicros = 0;
			Integer error = 0;
			try {
				loggedMicros = parseMicros(row.get(LOG_TIME));
				Matcher m = DURATION.matcher(message);
				if ("LOG".equals(row.get(SEVERITY)) && m.matches()) {
					String step = m.group(2);
					if (step.equals("parse") || step.equals("bind")) {
						ignored++; // 재생은 문장을 통째로 보낸다. 준비 단계의 시간은 따로 재현하지 않는다
						continue;
					}
					durationMicros = new BigDecimal(m.group(1)).movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact();
					sql = step.equals("execute") ? PgParameters.inline(m.group(3), row.get(DETAIL)) : m.group(3);
				}
				else if ("ERROR".equals(row.get(SEVERITY)) && row.get(QUERY) != null) {
					sql = row.get(QUERY);
					error = FAILED;
				}
				else if (message.startsWith("disconnection: ")) {
					Session session = sessions.remove(sessionId);
					long at = session == null ? loggedMicros : Math.max(loggedMicros, session.lastStartMicros);
					buffer.add(TraceRecord.close(sessionId, at));
					continue;
				}
				else {
					ignored++; // 접속 안내, 체크포인트 등
					continue;
				}
			}
			catch (ArithmeticException | NumberFormatException | DateTimeException malformed) {
				skipped++;
				continue;
			}

			sql = stripSemicolon(sql.strip());
			if (SqlWords.ownStatement(sql)) {
				ignored++; // 이 도구의 표본기가 보낸 조회
				continue;
			}
			Session session = sessions.computeIfAbsent(sessionId, id -> new Session());
			long start = loggedMicros - durationMicros;
			if (start < session.lastStartMicros) {
				start = session.lastStartMicros;
				clamped++;
			}
			session.lastStartMicros = start;

			PgTransactionTracker.Kind kind = PgTransactionTracker.kindOf(sql);
			boolean isEnd = kind == PgTransactionTracker.Kind.END || kind == PgTransactionTracker.Kind.END_AND_CHAIN;
			boolean orphanEnd = isEnd && !session.tracker.inTransaction();
			Long txn = session.tracker.assign(kind);
			int index = buffer.add(new TraceRecord(sessionId, start, durationMicros, sql, row.get(DATABASE),
					error, null, txn, null));
			if (kind == PgTransactionTracker.Kind.OTHER) {
				session.head.statement(index, sql);
			}
			else {
				session.head.boundary(index, orphanEnd, sql, headPolicy, buffer);
			}
		}

		RecordBuffer.Written w = buffer.write(trace, TraceHeader.of("postgresql", "csvlog"));
		return new ConvertSummary(entries, w.statements(), w.closes(), w.sessions(), ignored,
				buffer.droppedCount(), skipped, clamped);
	}

	private static String stripSemicolon(String s) {
		return s.endsWith(";") ? s.substring(0, s.length() - 1).stripTrailing() : s;
	}

	/** {@code 2026-10-11 01:31:09.310 UTC}. 시간대 약어는 뜻이 하나로 정해지지 않아 UTC와 숫자 오프셋, 지역 이름만 받는다. */
	static long parseMicros(String logTime) {
		Matcher m = logTime == null ? null : TIMESTAMP.matcher(logTime);
		if (m == null || !m.matches()) {
			throw new DateTimeException("시각을 읽을 수 없다: " + logTime);
		}
		LocalDateTime local = LocalDateTime.parse(m.group(1) + "T" + m.group(2));
		String zone = m.group(3);
		ZoneId zoneId;
		if (zone.equals("UTC") || zone.equals("GMT") || zone.equals("Z")) {
			zoneId = ZoneOffset.UTC;
		}
		else {
			try {
				zoneId = ZoneId.of(zone.matches("[+-]\\d\\d") ? zone + ":00" : zone);
			}
			catch (DateTimeException e) {
				throw new IllegalArgumentException("로그의 시간대 '" + zone + "'을 해석할 수 없다. "
						+ "log_timezone을 UTC로 두고 녹화해야 한다");
			}
		}
		Instant t = local.atZone(zoneId).toInstant();
		return t.getEpochSecond() * 1_000_000 + t.getNano() / 1_000;
	}
}
