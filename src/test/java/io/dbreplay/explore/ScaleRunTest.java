package io.dbreplay.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ScaleRunTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC);
	private static final Map<String, String> AFTER = Map.of("DBREPLAY_READ_NODES", "3");
	private static final Map<String, String> BEFORE = Map.of("DBREPLAY_READ_NODES", "2");

	/** 부른 명령과 받은 노드 수를 적어 두고, 정해 둔 종료 코드를 돌려주는 가짜. */
	private static final class FakeRunner implements ScaleRun.Runner {
		final Map<String, Integer> exits;
		final List<String> calls = new ArrayList<>();

		FakeRunner(Map<String, Integer> exits) {
			this.exits = exits;
		}

		@Override
		public int run(String command, Map<String, String> env) throws IOException {
			calls.add(command + " " + env.get("DBREPLAY_READ_NODES"));
			if (command.equals("cannot-start")) {
				throw new IOException("no such file: secret-in-here");
			}
			return exits.getOrDefault(command, 0);
		}
	}

	private static ScaleRun.Outcome run(FakeRunner runner, String verify, String rollback) throws InterruptedException {
		return ScaleRun.execute("scale", verify, rollback, AFTER, BEFORE, 2, 3, runner, c -> "fp:" + c, CLOCK);
	}

	/** 확장과 확인이 성공하면 되돌리지 않는다. */
	@Test
	void verifiesAfterScalingAndDoesNotRollBackOnSuccess() throws InterruptedException {
		FakeRunner runner = new FakeRunner(Map.of());
		ScaleRun.Outcome o = run(runner, "verify", "rollback");
		assertTrue(o.succeeded());
		assertEquals(List.of("scale 3", "verify 3"), runner.calls);
		assertEquals(List.of("scale", "verify"), o.entries().stream().map(ScaleRun.Entry::step).toList());
	}

	/** 확인이 실패하면 확장 전의 노드 수로 되돌리기 명령을 부르고, 세 단계가 기록에 남는다. */
	@Test
	void rollsBackWithThePreviousNodeCountWhenVerificationFails() throws InterruptedException {
		FakeRunner runner = new FakeRunner(Map.of("verify", 3));
		ScaleRun.Outcome o = run(runner, "verify", "rollback");
		assertFalse(o.succeeded());
		assertEquals("verify", o.failedStep());
		assertTrue(o.rolledBack());
		assertTrue(o.rollbackSucceeded());
		assertEquals(List.of("scale 3", "verify 3", "rollback 2"), runner.calls);
		ScaleRun.Entry verify = o.entries().get(1);
		assertEquals(3, verify.exit());
		assertEquals("fp:verify", verify.command(), "기록에는 지문만 남는다");
		assertEquals("2026-10-15T00:00:00Z", verify.at());
	}

	/** 확장이 실패하면 확인하지 않고 되돌린다. 되돌리기 명령이 없으면 그대로 둔다. */
	@Test
	void skipsVerificationWhenScalingFails() throws InterruptedException {
		FakeRunner runner = new FakeRunner(Map.of("scale", 1));
		ScaleRun.Outcome o = run(runner, "verify", null);
		assertEquals("scale", o.failedStep());
		assertFalse(o.rolledBack());
		assertEquals(List.of("scale 3"), runner.calls);
	}

	/** 명령을 띄우지 못하면 종료 코드 -1로 적는다. 예외의 메시지(원문이 들 수 있다)는 버린다. */
	@Test
	void recordsACommandThatCouldNotStart() throws InterruptedException {
		FakeRunner runner = new FakeRunner(Map.of());
		ScaleRun.Outcome o = ScaleRun.execute("cannot-start", null, "rollback", AFTER, BEFORE, 2, 3, runner, c -> "fp", CLOCK);
		assertEquals(-1, o.entries().getFirst().exit());
		assertTrue(o.rolledBack());
		assertNull(o.entries().stream().filter(e -> e.command().contains("secret")).findAny().orElse(null));
	}
}
