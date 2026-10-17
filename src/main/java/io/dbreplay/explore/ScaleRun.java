package io.dbreplay.explore;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 확장·축소 명령을 실행하고, 확인하고, 실패하면 되돌린다. 글은 제안을 운영 워크플로로 실행하고 추적, 관찰, 롤백이 된다고 한다
 * (원문 대조 54, 55, #86). 이 도구는 명령을 사용자에게서 받으므로, 확인과 되돌리기도 사용자의 명령이다.
 *
 * <ol>
 * <li>확장 명령. 0이 아니면 실패다
 * <li>확장이 성공했고 확인 명령이 있으면 확인한다. 0이 아니면 실패다
 * <li>어느 쪽이든 실패했고 되돌리기 명령이 있으면 되돌린다. 되돌리기는 확장 전의 노드 수를 환경 변수로 받는다
 * </ol>
 *
 * <p>단계마다 기록을 남긴다. 명령의 원문은 암호가 들어 있을 수 있어 지문만 적는다.
 */
public final class ScaleRun {

	/** 명령 하나를 실행하고 종료 코드를 돌려준다. 테스트에서는 가짜를 넣는다. */
	@FunctionalInterface
	public interface Runner {
		int run(String command, Map<String, String> env) throws IOException, InterruptedException;
	}

	/**
	 * 실행 기록의 한 줄.
	 *
	 * @param at              단계를 시작한 시각 (ISO-8601)
	 * @param step            scale, verify, rollback
	 * @param command         명령의 지문
	 * @param exit            종료 코드. 명령을 띄우지 못했으면 -1
	 * @param millis          걸린 시간
	 * @param readNodesBefore 확장 전의 읽기 노드 수
	 * @param readNodesAfter  권한 읽기 노드 수
	 */
	public record Entry(String at, String step, String command, int exit, long millis, int readNodesBefore, int readNodesAfter) {
	}

	/**
	 * @param succeeded       확장과 확인이 모두 성공했는가
	 * @param failedStep      실패한 단계(scale, verify). 성공했으면 null
	 * @param rolledBack      되돌리기 명령을 실행했는가
	 * @param rollbackSucceeded 되돌리기가 성공했는가. 실행하지 않았으면 false
	 * @param entries         단계마다의 기록
	 */
	public record Outcome(boolean succeeded, String failedStep, boolean rolledBack, boolean rollbackSucceeded, List<Entry> entries) {
	}

	private ScaleRun() {
	}

	/**
	 * @param verifyCommand   없으면 null
	 * @param rollbackCommand 없으면 null
	 * @param after           확장·확인 명령에 넘길 환경 변수(권한 노드 수)
	 * @param before          되돌리기 명령에 넘길 환경 변수(확장 전의 노드 수)
	 */
	public static Outcome execute(String scaleCommand, String verifyCommand, String rollbackCommand, Map<String, String> after,
			Map<String, String> before, int readNodesBefore, int readNodesAfter, Runner runner, Function<String, String> fingerprint,
			Clock clock) throws InterruptedException {
		List<Entry> entries = new ArrayList<>();
		String failed = null;
		if (step("scale", scaleCommand, after, readNodesBefore, readNodesAfter, runner, fingerprint, clock, entries) != 0) {
			failed = "scale";
		}
		else if (verifyCommand != null
				&& step("verify", verifyCommand, after, readNodesBefore, readNodesAfter, runner, fingerprint, clock, entries) != 0) {
			failed = "verify";
		}
		boolean rolledBack = false, rollbackSucceeded = false;
		if (failed != null && rollbackCommand != null) {
			rolledBack = true;
			rollbackSucceeded = step("rollback", rollbackCommand, before, readNodesBefore, readNodesAfter, runner, fingerprint, clock,
					entries) == 0;
		}
		return new Outcome(failed == null, failed, rolledBack, rollbackSucceeded, List.copyOf(entries));
	}

	private static int step(String name, String command, Map<String, String> env, int before, int after, Runner runner,
			Function<String, String> fingerprint, Clock clock, List<Entry> entries) throws InterruptedException {
		String at = clock.instant().toString();
		long start = clock.millis();
		int exit;
		try {
			exit = runner.run(command, env);
		}
		catch (IOException e) {
			// 명령을 띄우지 못했다. 원문에 암호가 있을 수 있어 메시지에 싣지 않는다
			exit = -1;
		}
		entries.add(new Entry(at, name, fingerprint.apply(command), exit, clock.millis() - start, before, after));
		return exit;
	}
}
