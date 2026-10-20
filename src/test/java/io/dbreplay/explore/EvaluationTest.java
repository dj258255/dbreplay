package io.dbreplay.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.dbreplay.replay.Histogram;
import io.dbreplay.replay.ReplaySummary;
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
		assertEquals(80, traffic(80, 20).reads());
		assertEquals(20, traffic(80, 20).writes());
		assertEquals(800.0, traffic(80, 20).readQps(), 10, "100문장이 0.099초에 걸쳐 있다");
	}

	/** 대상 목록의 설정은 explore의 옵션을 그대로 받는다. 새 문법을 만들지 않는다. */
	@Test
	void readsTheManagedTargetsFile() throws IOException {
		ManagedTargets managed = new ObjectMapper().readValue("""
				{"targets": [
				  {"name": "orders", "evaluation": "eval/orders.json",
				   "options": {"--trace": "orders.trace.jsonl", "--url": "jdbc:mysql://db/orders", "--alarm-metric": "active_sessions>=32"}},
				  {"name": "users", "evaluation": "eval/users.json", "options": {}}
				]}
				""", ManagedTargets.class);

		assertEquals(List.of("orders", "users"), managed.targets().stream().map(ManagedTargets.Target::name).toList());
		assertEquals("active_sessions>=32", managed.targets().getFirst().options().get("--alarm-metric"));
	}

	@Test
	void survivesBeingWrittenToAFileAndReadBack() throws IOException {
		ObjectMapper json = new ObjectMapper();
		Evaluation read = json.readValue(json.writeValueAsString(evaluation()), Evaluation.class);

		assertEquals(evaluation(), read);
	}

	/**
	 * 지금 형식의 평가 파일을 그대로 읽어 다시 쓰면 글자가 같아야 한다. 되풀이하지 않은 평가 파일의 JSON이
	 * 이 묶음 뒤에도 달라지지 않는다는 증거다. 끝의 줄바꿈만 다르면 맞춘다.
	 */
	@Test
	void rewritesTheCurrentEvaluationFileExactly() throws IOException {
		String original = Files.readString(Path.of("src/test/resources/explore/evaluation-single.json"), StandardCharsets.UTF_8);
		ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
		Evaluation read = json.readValue(Path.of("src/test/resources/explore/evaluation-single.json").toFile(), Evaluation.class);
		String rewritten = json.writeValueAsString(read);

		assertEquals(original.stripTrailing(), rewritten.stripTrailing());
	}

	/** 되풀이한 평가는 상한 목록을 파일에 남기고, 다시 읽어도 범위가 그대로다. */
	@Test
	void keepsTheCeilingSpeedsThroughAFile() throws IOException {
		Evaluation repeated = new Evaluation("2026-10-01T00:00:00Z", 30, PROFILE, ALARMS, "transaction", traffic(80, 20),
				3.75, "OVERLOADED", 15_000, 4_000, 0.3, 0.5, List.of(8.5, 12.0, 8.5, 3.75, 8.0));
		ObjectMapper json = new ObjectMapper();

		Evaluation read = json.readValue(json.writeValueAsString(repeated), Evaluation.class);

		assertEquals(repeated, read);
		CeilingRange range = read.ceilingRange();
		assertEquals(3.75, range.min(), 1e-9);
		assertEquals(8.5, range.median(), 1e-9);
		assertEquals(12.0, range.max(), 1e-9);
		assertEquals(range.representative(), read.ceilingSpeed(), 1e-9, "상한은 범위의 최솟값이다");
	}

	/** ceilingRange와 level은 계산 메서드다. 평가 파일의 항목으로 찍히면 다시 읽을 때 깨진다. */
	@Test
	void doesNotWriteComputedMethodsAsFields() throws IOException {
		Evaluation repeated = new Evaluation("2026-10-01T00:00:00Z", 30, PROFILE, ALARMS, "transaction", traffic(80, 20),
				3.75, "OVERLOADED", 15_000, 4_000, 0.3, 0.5, List.of(8.5, 12.0, 8.5, 3.75, 8.0));
		String json = new ObjectMapper().writeValueAsString(repeated);

		assertTrue(json.contains("ceilingSpeeds"));
		assertFalse(json.contains("\"ceilingRange\""), "메서드는 항목이 아니다");
		assertFalse(json.contains("\"level\""), "메서드는 항목이 아니다");
	}

	/** 되풀이하지 않은 평가에는 상한 범위가 없다. */
	@Test
	void hasNoCeilingRangeWithoutRepeatedExploration() throws IOException {
		assertNull(evaluation().ceilingRange());
	}

	/** 손으로 고친 파일에 빈 상한 목록이 들어 있어도 죽지 않는다. 범위가 없는 것으로 본다. */
	@Test
	void hasNoCeilingRangeForAnEmptyCeilingSpeedsList() throws IOException {
		Evaluation empty = new Evaluation("2026-10-01T00:00:00Z", 30, PROFILE, ALARMS, "transaction", traffic(80, 20),
				5.0, "OVERLOADED", 20_000, 4_000, 0.3, 0.5, List.of());

		assertNull(empty.ceilingRange());
	}

	/**
	 * 알람이 울리지 않아 경계를 못 본 평가(stoppedBy null)의 파일 형식이 달라지지 않게 지킨다. stoppedBy가
	 * null이어도 "stoppedBy" : null 줄이 찍혀야 한다. 클래스 전체에 @JsonInclude(NON_NULL)을 붙이면 이 줄이
	 * 사라져 지금의 평가 파일과 달라진다.
	 */
	@Test
	void keepsTheStoppedByNullFieldInTheFile() throws IOException {
		Evaluation noCeiling = new Evaluation("2026-10-01T00:00:00Z", 30, PROFILE, ALARMS, "transaction", traffic(80, 20),
				5.0, null, 20_000, 4_000, 0.3, 0.5);
		String json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(noCeiling);

		assertTrue(json.contains("\"stoppedBy\" : null"), "경계를 못 본 평가도 stoppedBy 줄을 찍는다");
		assertFalse(json.contains("ceilingSpeeds"), "되풀이하지 않았으면 항목이 없다");
	}

	/** 라운드를 남긴 평가를 쓰고 다시 읽으면 배속, 판정, 이유, 지표의 평균과 최대가 그대로다. */
	@Test
	void keepsTheExplorationsThroughAFile() throws IOException {
		Evaluation withLog = new Evaluation("2026-10-01T00:00:00Z", 30, PROFILE, ALARMS, "transaction", traffic(80, 20),
				5.0, "OVERLOADED", 20_000, 4_000, 0.3, 0.5, null, List.of(List.of(
						new Evaluation.RoundLog(1.0, "HELD", null, 0.0, Map.of("active_sessions", List.of(1.0, 2.0))),
						new Evaluation.RoundLog(4.0, "OVERLOADED", "알람", 0.4, Map.of("active_sessions", List.of(3.0, 4.0))))));
		ObjectMapper json = new ObjectMapper();

		Evaluation read = json.readValue(json.writeValueAsString(withLog), Evaluation.class);

		assertEquals(withLog, read);
		assertEquals(2, read.explorations().getFirst().size());
		assertEquals(4.0, read.explorations().getFirst().get(1).speed(), 1e-9);
		assertEquals("OVERLOADED", read.explorations().getFirst().get(1).verdict());
		assertEquals("알람", read.explorations().getFirst().get(1).reason());
		assertEquals(List.of(3.0, 4.0), read.explorations().getFirst().get(1).metrics().get("active_sessions"));
	}

	/** 라운드를 남기지 않은 평가는 JSON에 explorations 이름이 없다. */
	@Test
	void omitsTheExplorationsWhenThereAreNone() throws IOException {
		String json = new ObjectMapper().writeValueAsString(evaluation());

		assertFalse(json.contains("explorations"));
	}

	/** 라운드 하나에서 리포트용 기록을 만든다. 지표는 이름 순으로 [평균, 최대]가 된다. */
	@Test
	void buildsARoundLogFromAnExplorerRound() {
		ReplaySummary summary = new ReplaySummary(1_000, 8, 0, 0, 0, 400, 0, 4, 30_000_000, 7_500_000,
				new Histogram(), new Histogram(), new Histogram());
		RoundData data = new RoundData(4, 1_000_000, 1_030_000, summary,
				Map.of("active_sessions", new double[] {1, 5}, "load1", new double[] {2, 9}));
		Explorer.Round round = new Explorer.Round(2, 4, data, new RoundJudge.Verdict(RoundJudge.State.OVERLOADED, "알람"));

		Evaluation.RoundLog log = Evaluation.RoundLog.of(round);

		assertEquals(4.0, log.speed(), 1e-9);
		assertEquals("OVERLOADED", log.verdict());
		assertEquals("알람", log.reason());
		assertEquals(data.distortion(), log.distortion(), 1e-9);
		assertEquals(Map.of("active_sessions", List.of(3.0, 5.0), "load1", List.of(5.5, 9.0)), log.metrics());
		assertEquals(List.of("active_sessions", "load1"), List.copyOf(log.metrics().keySet()), "이름 순");
	}

	/** 종 DB를 함께 튼 평가를 쓰고 다시 읽으면 같다. Replica가 낸 수위가 맞다. */
	@Test
	void keepsTheReplicaThroughAFile() throws IOException {
		Evaluation.Replica replica = new Evaluation.Replica(traffic(80, 20), 20_000, 2_000);
		Evaluation withReplica = new Evaluation("2026-10-01T00:00:00Z", 30, PROFILE, ALARMS, "transaction", traffic(80, 20),
				5.0, "OVERLOADED", 20_000, 4_000, 0.3, 0.5, null, null, replica);
		ObjectMapper json = new ObjectMapper();

		Evaluation read = json.readValue(json.writeValueAsString(withReplica), Evaluation.class);

		assertEquals(withReplica, read);
		assertEquals(0.1, read.replica().level(0.3, 0.5).level(), 1e-9, "2000 ÷ 20000 = 10%");
		assertEquals(WaterLevel.Verdict.SURPLUS, read.replica().level(0.3, 0.5).verdict());
	}

	/**
	 * 종 DB만의 건강 구간을 적으면 종 DB의 판정은 그 구간으로 나오고 주 DB의 구간은 그대로다. 적지 않은 예전 파일에는 그 칸이 없다.
	 * 원문 그림 11의 주 DB 0~80%, 종 DB 30~50%로 본다(#129).
	 */
	@Test
	void judgesTheReplicaWithItsOwnBandWhenOneIsGiven() throws IOException {
		Evaluation.Replica own = new Evaluation.Replica(traffic(80, 20), 20_000, 12_000, null, 0.3, 0.5);
		Evaluation primaryWide = new Evaluation("2026-10-01T00:00:00Z", 30, PROFILE, ALARMS, "transaction", traffic(80, 20),
				5.0, "OVERLOADED", 20_000, 12_000, 0.0, 0.8, null, null, own);
		ObjectMapper json = new ObjectMapper();

		Evaluation read = json.readValue(json.writeValueAsString(primaryWide), Evaluation.class);

		assertEquals(primaryWide, read);
		assertEquals(WaterLevel.Verdict.HEALTHY, read.level().verdict(), "주 DB 60%는 0~80% 안이다");
		assertEquals(WaterLevel.Verdict.SHORT, read.replica().level(read.low(), read.high()).verdict(), "종 DB 60%는 30~50%를 넘는다");
		assertFalse(json.writeValueAsString(new Evaluation.Replica(traffic(80, 20), 20_000, 2_000)).contains("low"));
	}

	/** 종 DB를 함께 틀지 않은 평가는 JSON에 replica 이름이 없다. */
	@Test
	void omitsTheReplicaWhenThereIsNone() throws IOException {
		String json = new ObjectMapper().writeValueAsString(evaluation());

		assertFalse(json.contains("replica"));
	}
}
