package io.dbreplay.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import io.dbreplay.explore.TrafficModel;
import io.dbreplay.explore.WaterLevel;

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

	/** 종 DB의 수위로 계산한 권고는 확장 명령을 실행하지 않는다. 종 DB의 상한은 알람으로 확인된 적이 없다. */
	@Test
	void refusesToApplyAdviceComputedFromTheReplicaLevel() {
		assertTrue(Main.scaleConfirmed(true, false), "주 DB의 권고이고 알람으로 확인됐다");
		assertFalse(Main.scaleConfirmed(true, true), "종 DB의 권고다");
		assertFalse(Main.scaleConfirmed(false, false), "알람으로 확인되지 않았다");
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
}
