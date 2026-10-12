package io.dbreplay.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.dbreplay.replay.Histogram;
import io.dbreplay.replay.ReplaySummary;

class ExplorerTest {

	/** 문장 1,000개 중 lateByReplayer개를 재생기가 늦게 내보낸 라운드. */
	private static RoundData round(double speed, long lateByReplayer, double... activeSessions) {
		return round(speed, lateByReplayer, Map.of("active_sessions", activeSessions));
	}

	private static RoundData round(double speed, long lateByReplayer, Map<String, double[]> metrics) {
		ReplaySummary summary = new ReplaySummary(1_000, 8, 0, 0, 0, lateByReplayer, 0, speed, 30_000_000,
				(long) (30e6 / speed), new Histogram(), new Histogram(), new Histogram());
		return new RoundData(speed, 1_000_000, 1_030_000, summary, metrics);
	}

	/** limit 배속을 넘으면 알람이 울리는 대상. */
	private static RoundJudge alarmAbove(double limit) {
		return new RoundJudge(List.of(r -> r.speed() > limit ? "알람" : null), 0.2);
	}

	private static List<Double> speeds(Explorer.Result result) {
		return result.rounds().stream().map(Explorer.Round::speed).toList();
	}

	private static Explorer.Result explore(RoundJudge judge, Explorer.Options options) throws Exception {
		return Explorer.explore(speed -> round(speed, 0), judge, options, r -> { });
	}

	@Test
	void doublesUntilAnAlarmFiresThenHalvesTheGap() throws Exception {
		Explorer.Result result = explore(alarmAbove(5.3), Explorer.Options.defaults());

		assertEquals(List.of(1.0, 2.0, 4.0, 8.0, 6.0, 5.0, 5.5), speeds(result));
		assertEquals(5.0, result.ceilingSpeed());
		assertEquals(RoundJudge.State.OVERLOADED, result.stoppedBy());
		assertEquals(RoundJudge.State.OVERLOADED, result.rounds().get(3).verdict().state());
		assertEquals(RoundJudge.State.HELD, result.rounds().get(5).verdict().state());
	}

	@Test
	void stopsAtMaxSpeedWhenNoAlarmEverFires() throws Exception {
		Explorer.Result result = explore(alarmAbove(1_000), new Explorer.Options(1, 6, 0.1, 12));

		assertEquals(List.of(1.0, 2.0, 4.0, 6.0), speeds(result));
		assertEquals(6.0, result.ceilingSpeed());
		assertNull(result.stoppedBy(), "알람을 본 적이 없으니 상한은 6 이상이라는 것만 안다");
	}

	@Test
	void reportsNoCeilingWhenTheFirstRoundAlarms() throws Exception {
		Explorer.Result result = explore(alarmAbove(0.5), Explorer.Options.defaults());

		assertEquals(List.of(1.0), speeds(result));
		assertEquals(0.0, result.ceilingSpeed());
	}

	@Test
	void honorsTheRoundLimit() throws Exception {
		Explorer.Result result = explore(alarmAbove(5.3), new Explorer.Options(1, 64, 0.001, 5));

		assertEquals(5, result.rounds().size());
		assertEquals(4.0, result.ceilingSpeed());
	}

	/** 알람은 안 울렸는데 재생기가 그 배속을 못 냈으면, 그 위는 본 적이 없는 것이다. */
	@Test
	void treatsADistortedRoundAsUnknownNotAsHeld() throws Exception {
		RoundJudge judge = alarmAbove(1_000);
		Explorer.Result result = Explorer.explore(speed -> round(speed, speed >= 8 ? 400 : 10), judge,
				Explorer.Options.defaults(), r -> { });

		assertEquals(RoundJudge.State.DISTORTED, result.rounds().get(3).verdict().state());
		assertEquals(RoundJudge.State.DISTORTED, result.stoppedBy());
		assertTrue(result.ceilingSpeed() < 8);
	}

	@Test
	void anAlarmWinsOverDistortion() throws Exception {
		RoundJudge.Verdict verdict = alarmAbove(1).judge(round(2, 900));

		assertEquals(RoundJudge.State.OVERLOADED, verdict.state());
		assertEquals("알람", verdict.reason());
	}

	@Test
	void refusesToJudgeWithoutAnAlarm() {
		assertThrows(IllegalArgumentException.class, () -> new RoundJudge(List.of(), 0.2));
	}

	@Test
	void metricAlarmNeedsConsecutiveSamples() throws Exception {
		Alarm alarm = Alarm.metric("active_sessions", 32, 3);

		assertNull(alarm.check(round(1, 0, 40, 40, 10, 40, 40)), "두 번 연속까지는 울리지 않는다");
		assertEquals("active_sessions이(가) 3번 연속 32 이상 (최대 50)", alarm.check(round(1, 0, 5, 32, 50, 33, 4)));
		assertNull(alarm.check(round(1, 0)), "뜬 값이 없으면 울리지 않는다");
	}

	/** 지표 명령이 낸 이름에도 알람을 걸 수 있다. 조건 여럿은 쉼표로 잇는다. */
	@Test
	void parsesSeveralMetricConditions() throws Exception {
		List<Alarm> alarms = Alarm.metrics("active_sessions>=32, load1>=7.5", 2);
		RoundData busyHost = round(1, 0, Map.of("active_sessions", new double[] {3, 4}, "load1", new double[] {7.9, 8.2}));

		assertEquals(2, alarms.size());
		assertNull(alarms.get(0).check(busyHost));
		assertEquals("load1이(가) 2번 연속 7.5 이상 (최대 8.2)", alarms.get(1).check(busyHost));
		assertThrows(IllegalArgumentException.class, () -> Alarm.metrics("load1>8", 2));
	}

	@Test
	void summarizesAMetricOverTheRound() {
		RoundData data = round(1, 0, 2, 6, 10);

		assertEquals(10, data.maxActiveSessions());
		assertEquals(6.0, data.mean("active_sessions"), 1e-9);
		assertEquals(0.0, data.max("없는 지표"), 1e-9);
	}

	@Test
	void commandAlarmReadsTheExitCodeAndPassesTheRoundWindow() throws Exception {
		assertNull(Alarm.command("exit 0").check(round(2, 0)));
		assertEquals("cpu busy 92%", Alarm.command("echo 'cpu busy 92%'; echo detail; exit 3").check(round(2, 0)));
		assertEquals("알람 명령이 1(으)로 끝났다", Alarm.command("exit 1").check(round(2, 0)));
		assertNull(Alarm.command("test \"$DBREPLAY_SPEED\" = 2.0 && test \"$DBREPLAY_ROUND_START\" = 1000 "
				+ "&& test \"$DBREPLAY_ROUND_END\" = 1031").check(round(2, 0)));
	}

	/** 읽기는 노드 수로, 쓰기는 용량의 배수로 권한다. 수위가 과잉, 적정, 부족인 세 경우다. */
	@Test
	void advisesReadNodesFromTheLevelAndKeepsTheMinimum() {
		WaterLevel write = WaterLevel.of(800, 4_000);

		ScaleAdvice surplus = ScaleAdvice.of(WaterLevel.of(2_000, 16_000), write, 6, 2, 100);
		assertEquals(2, surplus.recommendedReadNodes(), "수위 12.5%면 계산은 1.5개지만 최소 2개를 지킨다");
		assertEquals(-4, surplus.readNodeChange());

		ScaleAdvice healthy = ScaleAdvice.of(WaterLevel.of(4_000, 10_000), write, 6, 2, 100);
		assertEquals(6, healthy.recommendedReadNodes(), "건강 구간 안이면 건드리지 않는다");

		ScaleAdvice shortOf = ScaleAdvice.of(WaterLevel.of(6_200, 10_000), WaterLevel.of(6_200, 10_000), 6, 2, 100);
		assertEquals(8, shortOf.recommendedReadNodes(), "수위 62%를 50%로 내리려면 6 × 0.62 ÷ 0.5 = 7.44, 올림해 8개");
		assertEquals(1.24, shortOf.writeCapacityFactor(), 1e-9, "쓰기는 노드 수가 아니라 용량의 배수다");
		assertEquals(WaterLevel.Verdict.SHORT, shortOf.writeVerdict());

		assertEquals(4, ScaleAdvice.of(WaterLevel.of(2_500, 10_000), write, 8, 1, 100).recommendedReadNodes(), "딱 떨어지면 올리지 않는다");
	}

	/** 상한이 0에 가깝게 나오면 계산한 노드 수가 터무니없이 커진다. 한도를 넘기지 않고, 넘었다는 것을 알린다. */
	@Test
	void neverAdvisesMoreNodesThanTheLimit() {
		WaterLevel write = WaterLevel.of(800, 4_000);

		ScaleAdvice huge = ScaleAdvice.of(WaterLevel.of(1_000_000, 1), write, 6, 2, 12);
		assertEquals(12, huge.recommendedReadNodes());
		assertTrue(huge.capped());

		ScaleAdvice noCeiling = ScaleAdvice.of(WaterLevel.of(1_000, 0), write, 6, 2, 12);
		assertEquals(12, noCeiling.recommendedReadNodes(), "상한이 0이면 수위가 무한대다");
		assertTrue(noCeiling.capped());

		ScaleAdvice unlimited = ScaleAdvice.of(WaterLevel.of(1_000_000, 1), write, 6, 2, Integer.MAX_VALUE);
		assertEquals(Integer.MAX_VALUE, unlimited.recommendedReadNodes(), "한도를 주지 않으면 잘린 값이 그대로 보이고, 명령은 실행되지 않는다");

		assertEquals(false, ScaleAdvice.of(WaterLevel.of(6_200, 10_000), write, 6, 2, 12).capped());
	}

	@Test
	void waterLevelFollowsTheArticlesDefinition() {
		WaterLevel surplus = WaterLevel.of(4_000, 16_000);
		assertEquals(0.25, surplus.level(), 1e-9);
		assertEquals(WaterLevel.Verdict.SURPLUS, surplus.verdict());
		assertEquals(0.5, surplus.capacityFactor(), 1e-9);

		assertEquals(WaterLevel.Verdict.HEALTHY, WaterLevel.of(4_000, 10_000).verdict());

		WaterLevel shortOf = WaterLevel.of(6_200, 10_000);
		assertEquals(WaterLevel.Verdict.SHORT, shortOf.verdict());
		assertEquals(1.24, shortOf.capacityFactor(), 1e-9);

		assertEquals(WaterLevel.Verdict.HEALTHY, new WaterLevel(6_200, 10_000, 0.4, 0.7).verdict(), "구간은 바꿀 수 있다");
	}
}
