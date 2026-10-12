package io.dbreplay.mysql;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

import io.dbreplay.convert.ConvertSummary;
import io.dbreplay.convert.HeadPolicy;
import io.dbreplay.convert.RecordBuffer;
import io.dbreplay.convert.SessionHead;
import io.dbreplay.convert.SqlWords;
import io.dbreplay.trace.TraceHeader;
import io.dbreplay.trace.TraceRecord;

/** MySQL 슬로우 로그를 트래픽 파일로 바꾼다. */
public final class SlowLogConverter {

	private static final class Session {
		final TransactionTracker tracker = new TransactionTracker();
		final SessionHead head = new SessionHead();
		long lastStartMicros = Long.MIN_VALUE;
	}

	private SlowLogConverter() {
	}

	public static ConvertSummary convert(Reader slowLog, Writer trace) throws IOException {
		return convert(slowLog, trace, HeadPolicy::byDefault);
	}

	public static ConvertSummary convert(Reader slowLog, Writer trace, HeadPolicy headPolicy) throws IOException {
		SlowLogParser parser = new SlowLogParser(slowLog);
		RecordBuffer buffer = new RecordBuffer();
		Map<Long, Session> sessions = new HashMap<>();
		// use db; 줄은 연결이 아니라 로그 파일 기준으로, 직전 항목과 데이터베이스가 다를 때만 찍힌다
		String currentDb = null;
		long entries = 0, ignored = 0, clamped = 0;

		for (SlowLogEntry e; (e = parser.next()) != null;) {
			entries++;
			if (e.useDb() != null) {
				currentDb = e.useDb();
			}
			Session session = sessions.computeIfAbsent(e.connectionId(), id -> new Session());
			long start = e.startMicros();
			if (start < session.lastStartMicros) {
				start = session.lastStartMicros;
				clamped++;
			}
			session.lastStartMicros = start;
			String sessionId = Long.toString(e.connectionId());

			if (e.adminCommand() != null) {
				if (e.adminCommand().equals("Quit")) {
					buffer.add(TraceRecord.close(sessionId, start));
					sessions.remove(e.connectionId());
				}
				else {
					ignored++; // Prepare, Ping 등
				}
				continue;
			}

			StatementKind kind = StatementKind.of(e.sql());
			if (kind == StatementKind.PREPARE || SqlWords.ownStatement(e.sql())) {
				ignored++;
				continue;
			}
			boolean orphanEnd = (kind == StatementKind.END || kind == StatementKind.END_AND_CHAIN)
					&& !session.tracker.inTransaction();
			Long txn = session.tracker.assign(kind, e.failed());
			int index = buffer.add(new TraceRecord(sessionId, start, e.durationMicros(), e.sql(), currentDb,
					e.errno(), e.lockMicros(), txn, e.rowsSent(), e.rowsExamined(), null));
			if (isBoundary(kind)) {
				session.head.boundary(index, orphanEnd, e.sql(), headPolicy, buffer);
			}
			else {
				session.head.statement(index, e.sql());
			}
		}

		RecordBuffer.Written w = buffer.write(trace, TraceHeader.of("mysql", "slow-log"));
		return new ConvertSummary(entries, w.statements(), w.closes(), w.sessions(), ignored,
				buffer.droppedCount(), parser.skipped(), clamped);
	}

	/** 이 문장을 보면 그 연결의 트랜잭션 상태를 알 수 있게 되는가. */
	private static boolean isBoundary(StatementKind kind) {
		return switch (kind) {
			case BEGIN, END, END_AND_CHAIN, IMPLICIT_COMMIT, AUTOCOMMIT_ON, AUTOCOMMIT_OFF -> true;
			default -> false;
		};
	}
}
