package io.dbreplay.mysql;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

import io.dbreplay.convert.ConvertSummary;
import io.dbreplay.convert.HeadPolicy;
import io.dbreplay.convert.LiteralMasker;
import io.dbreplay.convert.RecordBuffer;
import io.dbreplay.convert.ScopeFilter;
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
		try (RecordBuffer buffer = new RecordBuffer()) {
			return convert(slowLog, trace, headPolicy, buffer);
		}
	}

	/** @param buffer 레코드를 모아 정렬할 곳. 메모리 한도와 임시 폴더를 정해서 넘긴다. 닫는 것은 부른 쪽이 한다 */
	public static ConvertSummary convert(Reader slowLog, Writer trace, HeadPolicy headPolicy, RecordBuffer buffer)
			throws IOException {
		return convert(slowLog, trace, headPolicy, buffer, null, null);
	}

	/**
	 * @param masker   리터럴을 가릴 때 쓴다. 가리지 않으면 null. 문장의 종류와 트랜잭션 경계는 가리기 전의 문장으로 본다
	 * @param maskName 머리말에 적을 가림 수준. 가리지 않으면 null
	 */
	public static ConvertSummary convert(Reader slowLog, Writer trace, HeadPolicy headPolicy, RecordBuffer buffer,
			LiteralMasker masker, String maskName) throws IOException {
		return convert(slowLog, trace, headPolicy, buffer, masker, maskName, ScopeFilter.all());
	}

	/** @param scope 재생 대상의 SQL만 남긴다. 계정은 {@code User@Host:} 줄에서, 데이터베이스는 {@code use} 줄에서 읽는다 */
	public static ConvertSummary convert(Reader slowLog, Writer trace, HeadPolicy headPolicy, RecordBuffer buffer,
			LiteralMasker masker, String maskName, ScopeFilter scope) throws IOException {
		return convert(new SlowLogParser(slowLog), "slow-log", trace, headPolicy, buffer, masker, maskName, scope);
	}

	/**
	 * @param parser 항목을 내는 곳. 서버 로그나 패킷 캡처
	 * @param source 트래픽 파일의 머리말에 적을 녹화 입력의 종류
	 */
	public static ConvertSummary convert(SlowLogSource parser, String source, Writer trace, HeadPolicy headPolicy,
			RecordBuffer buffer, LiteralMasker masker, String maskName, ScopeFilter scope) throws IOException {
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
					if (scope.keepsClose(sessionId)) {
						buffer.add(TraceRecord.close(sessionId, start));
					}
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
			// 걸러 내는 문장도 트랜잭션 추적에는 넣는다. 걸러 낸 COMMIT을 못 보면 남긴 문장들이 끝난 트랜잭션에 묶인다
			Long txn = session.tracker.assign(kind, e.failed());
			if (!scope.keeps(sessionId, currentDb, e.user())) {
				continue;
			}
			long index = buffer.add(new TraceRecord(sessionId, start, e.durationMicros(),
					masker == null ? e.sql() : masker.mask(e.sql()), currentDb,
					e.errno(), e.lockMicros() < 0 ? null : e.lockMicros(), txn, e.rowsSent(),
				e.rowsExamined() < 0 ? null : e.rowsExamined(), null));
			if (isBoundary(kind)) {
				session.head.boundary(sessionId, index, orphanEnd, e.sql(), headPolicy, buffer);
			}
			else {
				session.head.statement(index, e.sql());
			}
		}

		RecordBuffer.Written w = buffer.write(trace, TraceHeader.of("mysql", source).withMask(maskName).withScope(scope.describe()));
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
