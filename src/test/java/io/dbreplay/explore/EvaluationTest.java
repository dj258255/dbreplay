package io.dbreplay.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.dbreplay.trace.TraceHeader;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;
import io.dbreplay.trace.TraceWriter;

class EvaluationTest {

	private static final Map<String, String> PROFILE = Map.of("version", "8.4.11", "innodb_buffer_pool_size", "134217728");
	private static final Map<String, String> ALARMS = Map.of("alarm-active-sessions", "32", "alarm-sustain", "3");

	/** 읽기 selects개와 쓰기 updates개로 된 트래픽. */
	private static TrafficModel traffic(int selects, int updates) throws IOException {
		StringWriter out = new StringWriter();
		try (TraceWriter w = new TraceWriter(out, TraceHeader.of("mysql", "test"))) {
			for (int i = 0; i < selects + updates; i++) {
				String sql = i < selects ? "SELECT c FROM t WHERE id = " + i : "UPDATE t SET k = k + 1 WHERE id = " + i;
				w.write(new TraceRecord("A", i * 1_000L, 100, sql, null, 0, null, null, null));
			}
		}
		try (TraceReader reader = new TraceReader(new StringReader(out.toString()))) {
			return TrafficModel.of(reader);
		}
	}

	private static Evaluation evaluation() throws IOException {
		return new Evaluation("2026-10-01T00:00:00Z", 30, PROFILE, ALARMS, "transaction", traffic(80, 20),
				5.0, "OVERLOADED", 20_000, 4_000, 0.3, 0.5);
	}

	private static final Instant IN_TIME = Instant.parse("2026-10-20T00:00:00Z");

	@Test
	void staysValidWhileNothingChanged() throws IOException {
		assertEquals(List.of(), evaluation().invalidReasons(IN_TIME, PROFILE, ALARMS, traffic(78, 22), 0.2));
		assertEquals(0.2, evaluation().level().level(), 1e-9);
	}

	@Test
	void expiresAfterItsValidDays() throws IOException {
		List<String> reasons = evaluation().invalidReasons(Instant.parse("2026-11-01T00:00:00Z"), PROFILE, ALARMS, null, 0.2);

		assertEquals(1, reasons.size());
		assertTrue(reasons.getFirst().startsWith("유효 기간이 지났다"));
	}

	/** 글이 든 무효 조건: 버전과 파라미터, 알람 임계값, 트래픽 모양. */
	@Test
	void becomesInvalidWhenVersionParametersAlarmsOrTrafficChange() throws IOException {
		Map<String, String> upgraded = Map.of("version", "8.4.12", "innodb_buffer_pool_size", "134217728");
		Map<String, String> looser = Map.of("alarm-active-sessions", "64", "alarm-sustain", "3");

		List<String> reasons = evaluation().invalidReasons(IN_TIME, upgraded, looser, traffic(20, 80), 0.2);

		assertEquals(List.of(
				"버전이나 파라미터가 바뀌었다: version 8.4.11 → 8.4.12",
				"알람 기준이 바뀌었다: alarm-active-sessions 32 → 64",
				"트래픽 모양이 바뀌었다 (거리 0.60)"), reasons);
	}

	@Test
	void measuresHowFarTwoTrafficShapesAre() throws IOException {
		assertEquals(0.0, traffic(80, 20).distance(traffic(800, 200)), 1e-9);
		assertEquals(0.6, traffic(80, 20).distance(traffic(20, 80)), 1e-9);
		assertEquals(Map.of("SELECT c FROM t WHERE id = ?", 0.8, "UPDATE t SET k = k + ? WHERE id = ?", 0.2), traffic(80, 20).shares());
		assertEquals(100, traffic(80, 20).statements());
	}

	@Test
	void survivesBeingWrittenToAFileAndReadBack() throws IOException {
		ObjectMapper json = new ObjectMapper();
		Evaluation read = json.readValue(json.writeValueAsString(evaluation()), Evaluation.class);

		assertEquals(evaluation(), read);
	}
}
