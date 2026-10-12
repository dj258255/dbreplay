package io.dbreplay.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.dbreplay.replay.ReplayResult;
import io.dbreplay.trace.TraceHeader;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;
import io.dbreplay.trace.TraceWriter;

class NewErrorsTest {

	private static ReplayResult result(long seq, int error, Integer origError) {
		return new ReplayResult(seq, "A", 0, 0, false, 10, error, 10, origError);
	}

	/** 8.0에서 되던 문장이 8.4에서 구문 오류가 되는 경우(실험 09). 어떤 모양이 무슨 오류로 몇 번 실패했는지가 나와야 한다. */
	@Test
	void groupsStatementsThatFailedOnlyInTheReplayByErrorAndShape() throws IOException {
		StringWriter file = new StringWriter();
		try (TraceWriter w = new TraceWriter(file, TraceHeader.of("mysql", "test"))) {
			w.write(new TraceRecord("A", 1, 10, "SELECT id AS qualify FROM t WHERE id = 1", null, 0, null, null, null)); // 0
			w.write(new TraceRecord("A", 2, 10, "SELECT c FROM t WHERE id = 5", null, 0, null, null, null));             // 1
			w.write(new TraceRecord("A", 3, 10, "SELECT id AS qualify FROM t WHERE id = 2", null, 0, null, null, null)); // 2
			w.write(new TraceRecord("A", 4, 10, "INSERT INTO t VALUES (1)", null, 1062, null, null, null));              // 3
			w.write(new TraceRecord("A", 5, 10, "UPDATE t SET c = 1 WHERE id = 9", null, null, null, null, null));       // 4
			w.write(TraceRecord.close("A", 6));                                                                         // 5
		}
		List<ReplayResult> passed = new ArrayList<>();
		NewErrors errors = new NewErrors(passed::add);
		errors.accept(result(0, 1064, 0));
		errors.accept(result(1, 0, 0));
		errors.accept(result(2, 1064, 0));
		errors.accept(result(3, 1062, 1062)); // 원본에서도 실패했다. 새로 난 오류가 아니다
		errors.accept(result(4, 1205, null)); // 원본의 성공 여부를 모른다. 든다

		assertEquals(5, passed.size(), "결과는 다음 곳으로 그대로 넘긴다");
		assertEquals(3, errors.count());
		List<NewErrors.Row> rows;
		try (TraceReader trace = new TraceReader(new StringReader(file.toString()))) {
			rows = errors.rows(trace);
		}
		assertEquals(2, rows.size());
		assertEquals(new NewErrors.Row(1064, "SELECT id AS qualify FROM t WHERE id = ?", 2, "SELECT id AS qualify FROM t WHERE id = 1"),
				rows.get(0));
		assertEquals(1205, rows.get(1).error());
		assertEquals(1, rows.get(1).count());
	}
}
