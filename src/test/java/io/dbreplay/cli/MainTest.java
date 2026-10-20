package io.dbreplay.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.dbreplay.explore.CeilingRange;
import io.dbreplay.explore.Evaluation;
import io.dbreplay.explore.Explorer;
import io.dbreplay.explore.RoundJudge;
import io.dbreplay.explore.ScaleRun;
import io.dbreplay.explore.TrafficModel;
import io.dbreplay.explore.WaterLevel;
import io.dbreplay.explore.ZonePlan;
import io.dbreplay.replay.ProgressListener;

class MainTest {

	/** 지문을 적기 전에 만든 평가 파일로 물어도 명령의 원문이 답에 나오면 안 된다. */
	@Test
	void neverShowsTheRawCommandOfAnOldEvaluation() {
		String command = "mysql -uroot -psecret -e 'select 1'";
		Evaluation old = new Evaluation("2026-10-01T00:00:00Z", 30, Map.of(), Map.of("alarm-command", command), "transaction",
				new TrafficModel(0, 0, Map.of()), 5, null, 100, 20, 0.3, 0.5);
		Instant now = Instant.parse("2026-10-02T00:00:00Z");

		Evaluation safe = Main.withoutRawCommand(old);
		List<String> same = safe.invalidReasons(now, Map.of(), Map.of("alarm-command", Main.fingerprint(command)), null, 0.2);
		List<String> changed = safe.invalidReasons(now, Map.of(), Map.of("alarm-command", Main.fingerprint("other")), null, 0.2);

		assertEquals(List.of(), same, "같은 명령이면 예전 파일도 유효하다");
		assertEquals(1, changed.size());
		assertFalse(changed.getFirst().contains("secret"));
	}

	/** 평가 파일에는 알람 명령의 원문이 아니라 지문이 남는다. 명령에 암호가 들어 있어도 파일로 새지 않는다. */
	@Test
	void fingerprintsACommandWithoutKeepingItsText() {
		String command = "mysql -uroot -psecret -e 'select 1'";
		String fingerprint = Main.fingerprint(command);

		assertFalse(fingerprint.contains("secret"));
		assertEquals(fingerprint, Main.fingerprint(command), "같은 명령이면 같은 지문이어야 바뀌었는지를 알 수 있다");
		assertNotEquals(fingerprint, Main.fingerprint(command + " "));
		assertEquals("sha256:".length() + 16, fingerprint.length());
	}

	/** --repeat을 주지 않으면 한 번, 정수를 주면 그 횟수다. 잘못 준 값은 탐색을 시작하기 전에 거른다. */
	@Test
	void parsesTheRepeatCountAndRefusesBadValues() {
		assertEquals(1, Main.repeat(Map.of()));
		assertEquals(5, Main.repeat(Map.of("--repeat", "5")));
		assertThrows(IllegalArgumentException.class, () -> Main.repeat(Map.of("--repeat", "0")));
		assertThrows(IllegalArgumentException.class, () -> Main.repeat(Map.of("--repeat", "-1")));
		assertThrows(IllegalArgumentException.class, () -> Main.repeat(Map.of("--repeat", "2.5")));
		assertThrows(IllegalArgumentException.class, () -> Main.repeat(Map.of("--repeat", "abc")));
	}

	/** 되풀이한 상한의 요약 줄. 첫 줄은 범위, 둘째 줄은 탐색마다의 값이다. */
	@Test
	void summarizesTheCeilingRangeOfExperimentTwelve() {
		CeilingRange range = CeilingRange.of(List.of(
				new Explorer.Result(List.of(), 8.5, RoundJudge.State.OVERLOADED),
				new Explorer.Result(List.of(), 12, RoundJudge.State.OVERLOADED),
				new Explorer.Result(List.of(), 8.5, RoundJudge.State.OVERLOADED),
				new Explorer.Result(List.of(), 3.75, RoundJudge.State.OVERLOADED),
				new Explorer.Result(List.of(), 8, RoundJudge.State.OVERLOADED)));
		List<String> lines = Main.rangeLines(range);

		assertEquals("상한: 최소 3.75, 중앙값 8.50, 최대 12.00배속 (5번 탐색, 퍼짐 97%)", lines.get(0));
		assertEquals("  탐색마다: 8.50, 12.00, 8.50, 3.75, 8.00", lines.get(1));
	}

	/** 운영에서 잰 qps의 평균. 없으면 지어내지 않고 이유를 알린다 (#82). */
	@Test
	void averagesTheMeasuredQpsOrExplainsWhyItIsMissing() {
		assertEquals(150.0, Main.meanQps(Map.of("qps", new double[] {100, 200}), false), 1e-9);
		IllegalStateException pg = assertThrows(IllegalStateException.class,
				() -> Main.meanQps(Map.of("tps", new double[] {10}), true));
		assertTrue(pg.getMessage().contains("pg_stat_statements"));
	}

	/** 확장이 실패했을 때의 문장. 무엇이 실패했고 되돌렸는지를 적고, 명령의 원문은 싣지 않는다 (#86). */
	@Test
	void describesAScaleFailure() {
		ScaleRun.Entry scale = new ScaleRun.Entry("t", "scale", "fp", 0, 1, 2, 3);
		ScaleRun.Entry verify = new ScaleRun.Entry("t", "verify", "fp", 4, 1, 2, 3);
		ScaleRun.Entry rollback = new ScaleRun.Entry("t", "rollback", "fp", 0, 1, 2, 3);
		assertEquals("확인 명령이 실패했다 (종료 코드 4). 되돌리기 명령을 실행했다",
				Main.scaleFailure(new ScaleRun.Outcome(false, "verify", true, true, List.of(scale, verify, rollback))));
		assertEquals("확장·축소 명령을 실행하지 못했다. 되돌리기 명령이 없어 그대로 두었다",
				Main.scaleFailure(new ScaleRun.Outcome(false, "scale", false, false,
						List.of(new ScaleRun.Entry("t", "scale", "fp", -1, 1, 2, 3)))));
		assertNull(Main.scaleFailure(new ScaleRun.Outcome(true, null, false, false, List.of(scale))));
	}

	/** 존별 권고 줄과 재해 복구 점검 줄 (#85). */
	@Test
	void describesTheZonePlan() {
		ZonePlan plan = ZonePlan.of(ZonePlan.parse("a=2,b=1,c=2"), 8, 1, 0.8);
		List<String> lines = Main.zoneLines(plan, 0.5);
		assertEquals("  존별: a 2 → 3, b 1 → 3, c 2 → 2 (가장 적은 존부터 채우고 가장 많은 존부터 뺀다)", lines.get(0));
		assertEquals("  가장 많은 존(a) 하나가 빠지면 남은 노드 5개의 읽기 수위가 80%다. 건강 구간의 위 끝(50%)을 넘는다", lines.get(1));
	}

	/** --zones와 --read-nodes가 어긋나면 거르고, 존마다의 최소는 전체 최소를 끌어올린다 (#85). */
	@Test
	void checksZonesAgainstReadNodes() {
		assertEquals(5, Main.readNodes(Map.of("--zones", "a=2,b=3")));
		assertThrows(IllegalArgumentException.class, () -> Main.readNodes(Map.of("--zones", "a=2,b=3", "--read-nodes", "4")));
		assertEquals(4, Main.minReadNodes(Map.of("--zones", "a=2,b=3", "--min-per-zone", "2")));
		assertEquals(3, Main.minReadNodes(Map.of("--zones", "a=2,b=3", "--min-read-nodes", "3")));
	}

	/** 한 번만 탐색했으면 상한이 흔들린다는 안내를 붙이고, 되풀이했으면 범위가 대신하므로 붙이지 않는다 (#76). */
	@Test
	void notesThatASingleExplorationCanSwing() {
		List<String> once = Main.singleRunNote(1);
		assertEquals(2, once.size());
		assertTrue(once.get(0).contains("한 번 탐색한 값이다"));
		assertTrue(once.get(1).contains("--repeat N"));
		assertEquals(List.of(), Main.singleRunNote(2));
		assertEquals(List.of(), Main.singleRunNote(5));
	}

	/**
	 * 같은 평가에 증가 비율 2를 주면 수위가 정확히 두 배가 되고, 판정과 노드 권고가 그 수위로 나온다. 원문 6.1절의 "8월 2일 트래픽의
	 * 2배"다(#132). 읽기 80%, 상한 5배속, 기준 초당 1,000이면 지금 수위 20%(과잉), 2배면 40%(적정)다.
	 */
	@Test
	void growthMultipliesTheBaseQpsBeforeJudging() {
		TrafficModel traffic = new TrafficModel(1_000, 1_000_000, Map.of(), 800, 200);
		Evaluation e = new Evaluation("2026-10-01T00:00:00Z", 30, Map.of(), Map.of(), "transaction", traffic,
				5.0, "OVERLOADED", 5_000, 1_000, 0.3, 0.5, null, null, null);

		List<String> once = Main.growthLines(e, 1_000, "평가 때의 값", 0, 1, null);
		List<String> twice = Main.growthLines(e, 1_000, "평가 때의 값", 0, 2, null);
		List<String> fourTimes = Main.growthLines(e, 1_000, "평가 때의 값", 0, 4, 2);

		assertTrue(once.get(1).contains("수위 20%") && once.get(1).contains("과잉"), once.get(1));
		assertTrue(twice.get(1).contains("초당 2000") && twice.get(1).contains("수위 40%") && twice.get(1).contains("적정"), twice.get(1));
		assertTrue(fourTimes.get(1).contains("수위 80%") && fourTimes.get(1).contains("부족"), fourTimes.get(1));
		assertTrue(fourTimes.contains("  읽기: 노드 2개 → 4개 (2개 늘린다)"), String.join("\n", fourTimes));
	}

	/** 증가 비율은 0보다 커야 한다. */
	@Test
	void rejectsANonPositiveGrowth() {
		assertThrows(IllegalArgumentException.class, () -> Main.checkQpsOptions(Map.of("--growth", "0")));
		assertThrows(IllegalArgumentException.class, () -> Main.checkQpsOptions(Map.of("--growth", "-1")));
	}

	/** --progress 파일은 머리줄 하나와 상태마다 한 줄이다. 시작할 때 예전 줄을 비운다 (#127). */
	@Test
	void writesProgressLinesUnderOneHeader(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("progress.tsv");
		Files.writeString(file, "예전 실행의 줄\n");
		Map<String, String> options = Map.of("--progress", file.toString());

		Main.clearProgress(options);
		ProgressListener listener = Main.progressFile(options, "8.00");
		listener.accept(new ProgressListener.Progress(0, 1.0, 120, 100, 90, 3, 1, 16, 37.4, false));
		listener.accept(new ProgressListener.Progress(0, 2.4, 200, 200, 200, 5, 1, 18, -1, true));

		List<String> lines = Files.readAllLines(file);
		assertEquals(List.of(Main.PROGRESS_HEADER, "8.00\t0\t1.0\t120\t100\t90\t10\t20\t3\t1\t16\t37",
				"8.00\t0\t끝 2.4\t200\t200\t200\t0\t0\t5\t1\t18\t-"), lines);
		assertNull(Main.progressFile(Map.of(), "-"));
	}

	/** 첫 라운드가 왜곡으로 끝났으면 대상의 한계를 본 적이 없다. "녹화한 부하보다 낮다"고 하지 않는다 (#146). */
	@Test
	void aFirstRoundEndedByDistortionDoesNotBlameTheTarget() {
		String distorted = Main.firstRoundFailed(1, RoundJudge.State.DISTORTED);
		assertFalse(distorted.contains("녹화한 부하보다 낮다"), distorted);
		assertTrue(distorted.contains("재생기") && distorted.contains("왜곡"), distorted);
	}

	/** 1분 아래는 초로 적는다 (#130). */
	@Test
	void writesShortLengthsInSeconds() {
		assertEquals("15초", Main.length(15_000_000));
		assertEquals("2.5분", Main.length(150_000_000));
	}

	/** 역할의 옵션이 있으면 그것을, 없으면 함께 쓰는 --level-*을, 그것도 없으면 30~50%를 쓴다 (#129). */
	@Test
	void picksTheHealthyBandPerRole() {
		assertEquals(new Main.Band(0.30, 0.50), Main.band(Map.of(), "--primary-level-"));
		assertEquals(new Main.Band(0.20, 0.60), Main.band(Map.of("--level-low", "0.2", "--level-high", "0.6"), "--replica-level-"));
		Map<String, String> figure11 = Map.of("--primary-level-low", "0", "--primary-level-high", "0.8");
		assertEquals(new Main.Band(0.0, 0.80), Main.band(figure11, "--primary-level-"));
		assertEquals(new Main.Band(0.30, 0.50), Main.band(figure11, "--replica-level-"));
	}

	/** 2배속에서 시작해 첫 라운드부터 못 버티면 1배와 2배 사이는 모른다. "녹화한 부하보다 낮다"고 하면 틀린다 (#128). */
	@Test
	void aFailedFirstRoundAboveOneSpeedDoesNotClaimTheRecordedLoadFails() {
		assertTrue(Main.firstRoundFailed(1, RoundJudge.State.OVERLOADED).endsWith("녹화한 부하보다 낮다"));
		String two = Main.firstRoundFailed(2, RoundJudge.State.OVERLOADED);
		assertTrue(two.contains("녹화한 부하의 2.00배보다 낮다"));
		assertTrue(two.contains("--start-speed 1"));
	}

	/** 중앙값이 0이면 퍼짐을 0%로 찍지 않는다. 실험 17에서 [5.5, 19, 0, 0, 0]이 "퍼짐 0%"로 나왔다 (#91). */
	@Test
	void doesNotCallItZeroSpreadWhenTheMedianIsZero() {
		CeilingRange split = CeilingRange.of(List.of(
				new Explorer.Result(List.of(), 5.5, RoundJudge.State.OVERLOADED),
				new Explorer.Result(List.of(), 19, RoundJudge.State.OVERLOADED),
				new Explorer.Result(List.of(), 0, RoundJudge.State.OVERLOADED),
				new Explorer.Result(List.of(), 0, RoundJudge.State.OVERLOADED),
				new Explorer.Result(List.of(), 0, RoundJudge.State.OVERLOADED)));
		assertEquals("상한: 최소 0.00, 중앙값 0.00, 최대 19.00배속 (5번 탐색, 퍼짐은 중앙값이 0이라 낼 수 없다)", Main.rangeLines(split).get(0));

		CeilingRange none = CeilingRange.of(List.of(
				new Explorer.Result(List.of(), 0, RoundJudge.State.OVERLOADED),
				new Explorer.Result(List.of(), 0, RoundJudge.State.OVERLOADED)));
		assertEquals(0.0, none.spread(), "전부 같으면 흔들림이 없다");
	}

	/** 알람 명령의 원문이 든 예전 평가를 고쳐도 되풀이한 상한 목록은 잃지 않는다. */
	@Test
	void keepsCeilingSpeedsWhenTheRawCommandIsFingerprinted() {
		Evaluation old = new Evaluation("2026-10-01T00:00:00Z", 30, Map.of(),
				Map.of("alarm-command", "mysql -uroot -psecret -e 'select 1'"), "transaction",
				new TrafficModel(0, 0, Map.of()), 5, null, 100, 20, 0.3, 0.5, List.of(5.0, 3.0));

		Evaluation safe = Main.withoutRawCommand(old);

		assertEquals(List.of(5.0, 3.0), safe.ceilingSpeeds());
	}

	/** 알람 명령의 원문이 든 예전 평가를 고쳐도 라운드 기록은 잃지 않는다. */
	@Test
	void keepsExplorationsWhenTheRawCommandIsFingerprinted() {
		List<List<Evaluation.RoundLog>> explorations = List.of(List.of(
				new Evaluation.RoundLog(1.0, "HELD", null, 0.0, Map.of("active_sessions", List.of(1.0, 2.0)))));
		Evaluation old = new Evaluation("2026-10-01T00:00:00Z", 30, Map.of(),
				Map.of("alarm-command", "mysql -uroot -psecret -e 'select 1'"), "transaction",
				new TrafficModel(0, 0, Map.of()), 5, null, 100, 20, 0.3, 0.5, null, explorations);

		Evaluation safe = Main.withoutRawCommand(old);

		assertEquals(explorations, safe.explorations());
	}

	/** 알람 명령의 원문이 든 예전 평가를 고쳐도 종 DB 기록은 잃지 않는다. */
	@Test
	void keepsReplicaWhenTheRawCommandIsFingerprinted() {
		Evaluation.Replica replica = new Evaluation.Replica(new TrafficModel(0, 0, Map.of()), 20_000, 2_000);
		Evaluation old = new Evaluation("2026-10-01T00:00:00Z", 30, Map.of(),
				Map.of("alarm-command", "mysql -uroot -psecret -e 'select 1'"), "transaction",
				new TrafficModel(0, 0, Map.of()), 5, null, 100, 20, 0.3, 0.5, null, null, replica);

		Evaluation safe = Main.withoutRawCommand(old);

		assertEquals(replica, safe.replica());
	}

	/** 종 DB의 수위 줄. 지금 초당 2,000, 상한 초당 10,000이면 20%, 과잉이다. */
	@Test
	void buildsTheReplicaLevelLine() {
		String line = Main.replicaLevelLine(2_000, 10_000, 0.3, 0.5);

		assertTrue(line.contains("20%"), line);
		assertTrue(line.contains("과잉"), line);
	}

	/** 읽기 노드 권고의 입력: 종 DB의 수위가 있으면 그것, 없으면 주 DB 트래픽의 읽기 수위. */
	@Test
	void picksTheReplicaLevelForReadNodeAdviceWhenPresent() {
		WaterLevel primaryRead = WaterLevel.of(4_000, 10_000);
		WaterLevel replica = WaterLevel.of(2_000, 10_000);

		assertEquals(replica, Main.readLevelForAdvice(replica, primaryRead));
		assertEquals(primaryRead, Main.readLevelForAdvice(null, primaryRead));
	}

	/** 종 DB의 수위로 계산한 권고는 종 DB의 알람으로 확인됐을 때만 확장 명령을 실행한다 (#53, #80). */
	@Test
	void refusesToApplyAdviceComputedFromTheReplicaLevel() {
		assertTrue(Main.scaleConfirmed(true, false, false), "주 DB의 권고이고 알람으로 확인됐다");
		assertFalse(Main.scaleConfirmed(true, true, false), "종 DB의 권고이고 종 DB의 상한은 어림값이다");
		assertTrue(Main.scaleConfirmed(true, true, true), "종 DB의 권고이고 종 DB의 알람이 탐색을 멈췄다");
		assertFalse(Main.scaleConfirmed(false, true, true), "알람으로 확인되지 않았다");
		assertFalse(Main.scaleConfirmed(false, false, false), "알람으로 확인되지 않았다");
	}

	/** 과부하로 판정된 가장 낮은 배속의 라운드를 멈춘 알람이 종 DB의 것일 때만 종 DB의 상한이 확인된 것이다 (#80). */
	@Test
	void confirmsTheReplicaCeilingOnlyWhenTheReplicaAlarmStoppedTheSearch() {
		Explorer.Result byReplica = new Explorer.Result(List.of(
				new Explorer.Round(1, 4, null, new RoundJudge.Verdict(RoundJudge.State.OVERLOADED, "active_sessions이(가) 3번 연속 32 이상")),
				new Explorer.Round(2, 2, null, new RoundJudge.Verdict(RoundJudge.State.OVERLOADED, "replica.active_sessions이(가) 3번 연속 32 이상")),
				new Explorer.Round(3, 1, null, new RoundJudge.Verdict(RoundJudge.State.HELD, null))), 1, RoundJudge.State.OVERLOADED);
		Explorer.Result byPrimary = new Explorer.Result(List.of(
				new Explorer.Round(1, 2, null, new RoundJudge.Verdict(RoundJudge.State.OVERLOADED, "active_sessions이(가) 3번 연속 32 이상")),
				new Explorer.Round(2, 1, null, new RoundJudge.Verdict(RoundJudge.State.HELD, null))), 1, RoundJudge.State.OVERLOADED);
		Explorer.Result noAlarm = new Explorer.Result(List.of(
				new Explorer.Round(1, 2, null, new RoundJudge.Verdict(RoundJudge.State.HELD, null))), 2, null);

		assertTrue(Main.replicaCeilingConfirmed(List.of(byReplica)));
		assertFalse(Main.replicaCeilingConfirmed(List.of(byPrimary)));
		assertFalse(Main.replicaCeilingConfirmed(List.of(byReplica, byPrimary)), "되풀이했으면 모든 탐색에서 그래야 한다");
		assertFalse(Main.replicaCeilingConfirmed(List.of(noAlarm)));
		assertFalse(Main.replicaCeilingConfirmed(List.of()));
	}

	/** check의 수 검사: 지금의 QPS 옵션에 음수·NaN·무한대를 주면 거른다. */
	@Test
	void rejectsBadCurrentQpsValues() {
		assertThrows(IllegalArgumentException.class, () -> Main.checkQpsOptions(Map.of("--current-qps", "-1")));
		assertThrows(IllegalArgumentException.class, () -> Main.checkQpsOptions(Map.of("--current-qps", "NaN")));
		assertThrows(IllegalArgumentException.class, () -> Main.checkQpsOptions(Map.of("--current-qps", "Infinity")));
		assertThrows(IllegalArgumentException.class, () -> Main.checkQpsOptions(Map.of("--current-replica-qps", "-1")));
		assertThrows(IllegalArgumentException.class, () -> Main.checkQpsOptions(Map.of("--current-replica-qps", "NaN")));
		assertThrows(IllegalArgumentException.class, () -> Main.checkQpsOptions(Map.of("--current-replica-qps", "Infinity")));
		Main.checkQpsOptions(Map.of("--current-qps", "0"));
	}

	/** 평가 때 하드웨어를 남기지 않았으면 지금 하드웨어를 줘도 비교하지 않는다. 모르는 것을 바뀌었다고 하면 안 된다. */
	@Test
	void doesNotCompareHardwareThatTheEvaluationDidNotRecord() {
		List<Map<String, String>> profiles = Main.comparableProfiles(Map.of("version", "8.4.11"),
				Map.of("version", "8.4.11", "host.cpus", "4"));

		assertEquals(Map.of("version", "8.4.11"), profiles.get(0));
		assertEquals(Map.of("version", "8.4.11"), profiles.get(1), "지금 쪽의 host. 항목을 뺀다");
	}

	/** 평가 때 하드웨어를 남겼는데 지금 --profile-command를 주지 않았으면 비교하지 않는다. */
	@Test
	void doesNotCompareHardwareWhenTheCurrentProfileHasNone() {
		List<Map<String, String>> profiles = Main.comparableProfiles(Map.of("version", "8.4.11", "host.cpus", "8"),
				Map.of("version", "8.4.11"));

		assertEquals(Map.of("version", "8.4.11"), profiles.get(0), "평가 쪽의 host. 항목을 뺀다");
		assertEquals(Map.of("version", "8.4.11"), profiles.get(1));
	}

	/** 양쪽에 다 하드웨어가 있으면 다른 파라미터와 똑같이 비교한다. */
	@Test
	void comparesHardwareWhenBothSidesHaveIt() {
		List<Map<String, String>> profiles = Main.comparableProfiles(Map.of("version", "8.4.11", "host.cpus", "8"),
				Map.of("version", "8.4.11", "host.cpus", "4"));

		assertEquals(Map.of("version", "8.4.11", "host.cpus", "8"), profiles.get(0));
		assertEquals(Map.of("version", "8.4.11", "host.cpus", "4"), profiles.get(1));

		Evaluation evaluation = new Evaluation("2026-10-01T00:00:00Z", 30, profiles.get(0), Map.of(), "transaction",
				new TrafficModel(0, 0, Map.of()), 5, null, 100, 20, 0.3, 0.5);
		List<String> reasons = evaluation.invalidReasons(Instant.parse("2026-10-02T00:00:00Z"), profiles.get(1),
				Map.of(), null, 0.2);

		assertTrue(reasons.stream().anyMatch(reason -> reason.contains("host.cpus 8 → 4")), reasons.toString());
	}

	/** 하드웨어가 양쪽에서 같으면 무효 이유가 없다. */
	@Test
	void reportsNoReasonsWhenHardwareIsUnchanged() {
		List<Map<String, String>> profiles = Main.comparableProfiles(Map.of("version", "8.4.11", "host.cpus", "8"),
				Map.of("version", "8.4.11", "host.cpus", "8"));

		Evaluation evaluation = new Evaluation("2026-10-01T00:00:00Z", 30, profiles.get(0), Map.of(), "transaction",
				new TrafficModel(0, 0, Map.of()), 5, null, 100, 20, 0.3, 0.5);
		List<String> reasons = evaluation.invalidReasons(Instant.parse("2026-10-02T00:00:00Z"), profiles.get(1),
				Map.of(), null, 0.2);

		assertEquals(List.of(), reasons);
	}

	/** 양쪽에 다 하드웨어가 있으면 비교했다는 문구다. */
	@Test
	void notesThatHardwareWasComparedWhenBothSidesHaveIt() {
		Map<String, String> withHost = Map.of("version", "8.4.11", "host.cpus", "8");

		assertEquals("하드웨어는 설정 명령이 낸 값으로 비교했다", Main.hardwareNote(withHost, withHost, true));
	}

	/** 평가 때 하드웨어를 남기지 않았으면 지금 무엇을 줘도 확인하지 않았다는 문구다. */
	@Test
	void notesNoHardwareWhenTheEvaluationDidNotRecordIt() {
		Map<String, String> withHost = Map.of("version", "8.4.11", "host.cpus", "8");
		Map<String, String> withoutHost = Map.of("version", "8.4.11");

		assertEquals("하드웨어 사양은 DB 접속으로 알 수 없어 확인하지 않았다",
				Main.hardwareNote(withoutHost, withHost, true));
	}

	/** 명령을 줬어도 아무 값도 내지 않아 지금 쪽에 host. 항목이 없으면 "비교했다"고 하면 안 된다. */
	@Test
	void notesThatTheCommandProducedNothingWhenOnlyTheEvaluationHasHardware() {
		Map<String, String> withHost = Map.of("version", "8.4.11", "host.cpus", "8");
		Map<String, String> withoutHost = Map.of("version", "8.4.11");

		assertEquals("평가 파일에 하드웨어가 있지만 --profile-command가 아무 값도 내지 않아 비교하지 않았다",
				Main.hardwareNote(withHost, withoutHost, true));
	}

	/** 평가 파일에는 하드웨어가 있는데 명령을 주지 않았으면 그 이유를 밝힌다. */
	@Test
	void notesThatTheCommandWasNotGivenWhenOnlyTheEvaluationHasHardware() {
		Map<String, String> withHost = Map.of("version", "8.4.11", "host.cpus", "8");
		Map<String, String> withoutHost = Map.of("version", "8.4.11");

		assertEquals("평가 파일에 하드웨어가 있지만 --profile-command를 주지 않아 비교하지 않았다",
				Main.hardwareNote(withHost, withoutHost, false));
	}

	private static final Set<PosixFilePermission> OWNER_ONLY =
			Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	/** POSIX 권한이 없는 파일 시스템에서는 권한 확인을 건너뛴다. */
	private static void requirePosix() {
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
				"POSIX 권한이 없는 파일 시스템에서는 권한을 확인하지 않는다");
	}

	/** convert가 쓴 트래픽 파일은 값이 그대로 든 SQL이 들어 있어 소유자만 읽을 수 있어야 한다. */
	@Test
	void makesTheConvertedTraceFilePrivate(@TempDir Path dir) throws Exception {
		requirePosix();
		Path in = dir.resolve("slow.log");
		Files.copy(Path.of("src/test/resources/mysql/exp01-slow.log"), in);
		Path out = dir.resolve("trace.jsonl");

		Main.main(new String[] { "convert", "--in", in.toString(), "--out", out.toString() });

		assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(out));
		assertTrue(Files.size(out) > 0);
	}

	/** 다른 사용자가 읽을 수 있던 출력 파일이 이미 있어도 convert 뒤에는 소유자만 읽을 수 있다. */
	@Test
	void replacesAWorldReadableTraceFile(@TempDir Path dir) throws Exception {
		requirePosix();
		Path in = dir.resolve("slow.log");
		Files.copy(Path.of("src/test/resources/mysql/exp01-slow.log"), in);
		Path out = dir.resolve("trace.jsonl");
		Files.createFile(out, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--")));

		Main.main(new String[] { "convert", "--in", in.toString(), "--out", out.toString() });

		assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(out));
		assertTrue(Files.size(out) > 0);
	}

	/** check --current-qps: 평가 파일의 상한은 그대로 두고 지금의 QPS로 수위를 다시 계산한다. */
	@Test
	void recomputesTheWaterLevelFromTheCurrentQps() {
		String shortOf = Main.currentQpsLine(20_000, 12_000, 0.3, 0.5);
		assertTrue(shortOf.contains("60%"), shortOf);
		assertTrue(shortOf.contains("부족"), shortOf);

		String healthy = Main.currentQpsLine(20_000, 8_000, 0.3, 0.5);
		assertTrue(healthy.contains("40%"), healthy);
		assertTrue(healthy.contains("적정"), healthy);
	}

	/** 라운드에서 지표를 읽다 실패했으면 탐색을 멈춘다. 그 뒤의 값이 없으면 못 본 것을 버틴 것으로 읽게 된다. */
	@Test
	void stopsTheExplorationWhenMetricsFailedMidRound() {
		Main.requireNoFailures(List.of());

		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> Main.requireNoFailures(List.of("DbMetricSource이(가) 지표를 읽다 멈췄다 (SQLException)")));

		assertTrue(e.getMessage().contains("DbMetricSource이(가) 지표를 읽다 멈췄다"), e.getMessage());
		assertTrue(e.getMessage().contains("탐색을 멈춘다"), e.getMessage());
	}

	/** 여러 서버에 나눠 붙는 URL로는 DB 지표를 뜨지 않는다. 붙어 보지도 않으므로 없는 주소여도 표본기가 만들어진다 (#108). */
	@Test
	void doesNotSampleDbMetricsThroughMultiHostUrls() throws Exception {
		io.dbreplay.replay.JdbcTarget spread = new io.dbreplay.replay.JdbcTarget(
				"jdbc:mysql:loadbalance://127.0.0.1:1,127.0.0.1:2/sbtest?connectTimeout=200", "u", "p");
		try (var sampler = Main.sampler(spread, Map.of())) {
			assertTrue(sampler != null);
		}
		assertThrows(IllegalArgumentException.class, () -> Main.sampler(spread,
				Map.of("--replica-url", "jdbc:mysql://127.0.0.1:3/sbtest", "--user", "u")));
		assertThrows(IllegalArgumentException.class, () -> Main.measureQps(spread, 1));
	}
}
