package io.dbreplay.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.dbreplay.mysql.SlowLogConverter;
import io.dbreplay.postgres.CsvLogConverter;
import io.dbreplay.trace.TraceHeader;
import io.dbreplay.trace.TraceRecord;

class RecordBufferTest {

	private static final TraceHeader HEADER = TraceHeader.of("mysql", "slow-log");

	@TempDir
	Path temp;

	/** 끝난 순서로 들어오는 레코드를 흉내 낸다. 시작 시각이 겹치는 것이 많고, 가끔 아주 오래 걸린 문장이 뒤늦게 온다. */
	private static List<TraceRecord> records(int n) {
		Random random = new Random(7);
		List<TraceRecord> list = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			long end = 1_000_000L + i * 10L;
			long duration = random.nextInt(100) == 0 ? 5_000 + random.nextInt(20_000) : random.nextInt(30);
			String session = Integer.toString(random.nextInt(8));
			list.add(random.nextInt(200) == 0 ? TraceRecord.close(session, end)
					: new TraceRecord(session, (end - duration) / 10 * 10, duration, "SELECT " + i + " /* 한글\t탭 */", "db", 0, 0L, null, null));
		}
		return list;
	}

	private static String write(RecordBuffer buffer, List<TraceRecord> records) throws IOException {
		for (TraceRecord r : records) {
			buffer.add(r);
		}
		StringWriter out = new StringWriter();
		buffer.write(out, HEADER);
		return out.toString();
	}

	/** 메모리를 얼마로 잡든 출력은 같다. 임시 파일로 나누고 여러 단계로 병합해도 글자 하나 다르지 않아야 한다. */
	@Test
	void spillingGivesTheSameOutputAsSortingInMemory() throws IOException {
		List<TraceRecord> records = records(5_000);
		String inMemory;
		try (RecordBuffer buffer = new RecordBuffer(temp, Long.MAX_VALUE)) {
			inMemory = write(buffer, records);
			assertEquals(0, buffer.spills());
		}
		// 한 번에 3개씩만 병합하게 해서 여러 단계를 거치게 한다
		try (RecordBuffer buffer = new RecordBuffer(temp, 40_000, 3)) {
			String spilled = write(buffer, records);
			assertTrue(buffer.spills() > 9, "임시 파일이 한 번에 병합할 수보다 많아야 여러 단계를 본다: " + buffer.spills());
			assertEquals(inMemory, spilled);
		}
	}

	@Test
	void outputIsOrderedByStartAndKeepsArrivalOrderOnTies() throws IOException {
		List<TraceRecord> records = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			// 시각이 전부 같다. 넣은 순서가 곧 한 연결 안의 순서다
			records.add(new TraceRecord("1", 500, 1, "SELECT " + i, null, 0, null, null, null));
		}
		try (RecordBuffer buffer = new RecordBuffer(temp, 5_000, 2)) {
			String[] lines = write(buffer, records).split("\n");
			assertEquals(301, lines.length);
			for (int i = 0; i < 300; i++) {
				assertTrue(lines[i + 1].contains("\"SELECT " + i + "\""), lines[i + 1]);
			}
		}
	}

	/** 버리기로 한 범위는 이미 임시 파일로 내려간 레코드에도 듣는다. 다른 연결의 같은 순번대는 건드리지 않는다. */
	@Test
	void dropsARangeOfOneSessionEvenAfterItWasSpilled() throws IOException {
		try (RecordBuffer buffer = new RecordBuffer(temp, 2_000, 2)) {
			List<Long> mine = new ArrayList<>();
			for (int i = 0; i < 100; i++) {
				mine.add(buffer.add(new TraceRecord("a", 1_000 + i, 1, "UPDATE a" + i, null, 0, null, null, null)));
				buffer.add(new TraceRecord("b", 1_000 + i, 1, "UPDATE b" + i, null, 0, null, null, null));
			}
			assertTrue(buffer.spills() > 0);
			buffer.dropRange("a", mine.get(0), mine.get(39));

			StringWriter out = new StringWriter();
			RecordBuffer.Written written = buffer.write(out, HEADER);
			assertEquals(160, written.statements());
			assertEquals(40, buffer.droppedCount());
			assertEquals(2, written.sessions());
			assertFalse(out.toString().contains("\"UPDATE a39\""));
			assertTrue(out.toString().contains("\"UPDATE a40\""));
			assertTrue(out.toString().contains("\"UPDATE b0\""));
		}
	}

	@Test
	void leavesNoTemporaryFilesBehind() throws IOException {
		try (RecordBuffer buffer = new RecordBuffer(temp, 10_000, 4)) {
			write(buffer, records(2_000));
			assertTrue(buffer.spills() > 0);
		}
		// 쓰지 않고 닫아도 지운다. 변환이 중간에 실패한 경우다
		try (RecordBuffer buffer = new RecordBuffer(temp, 10_000, 4)) {
			for (TraceRecord r : records(2_000)) {
				buffer.add(r);
			}
		}
		try (var files = Files.list(temp)) {
			assertEquals(List.of(), files.toList());
		}
	}

	/** 실제 서버에서 받은 로그를 작은 메모리로 변환해도 큰 메모리로 변환한 것과 같다. 머리 정책으로 빼는 것도 같다. */
	@Test
	void convertersGiveTheSameTraceWithATinyBuffer() throws IOException {
		HeadPolicy dropAll = head -> HeadPolicy.Decision.DROP;
		for (HeadPolicy policy : List.<HeadPolicy>of(HeadPolicy::byDefault, dropAll)) {
			StringWriter whole = new StringWriter();
			StringWriter split = new StringWriter();
			ConvertSummary a = SlowLogConverter.convert(resource("/mysql/exp01-slow.log"), whole, policy);
			try (RecordBuffer buffer = new RecordBuffer(temp, 3_000, 2)) {
				ConvertSummary b = SlowLogConverter.convert(resource("/mysql/exp01-slow.log"), split, policy, buffer);
				assertTrue(buffer.spills() > 2);
				assertEquals(a, b);
			}
			assertEquals(whole.toString(), split.toString());

			whole = new StringWriter();
			split = new StringWriter();
			a = CsvLogConverter.convert(resource("/postgres/exp04-pgbench.csv"), whole, policy);
			try (RecordBuffer buffer = new RecordBuffer(temp, 3_000, 2)) {
				ConvertSummary b = CsvLogConverter.convert(resource("/postgres/exp04-pgbench.csv"), split, policy, buffer);
				assertTrue(buffer.spills() > 2);
				assertEquals(a, b);
			}
			assertEquals(whole.toString(), split.toString());
		}
	}

	private static Reader resource(String name) {
		return new InputStreamReader(RecordBufferTest.class.getResourceAsStream(name), StandardCharsets.UTF_8);
	}
}
