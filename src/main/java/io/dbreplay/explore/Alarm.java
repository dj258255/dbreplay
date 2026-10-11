package io.dbreplay.explore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 한 라운드 동안 알람이 울렸는지 본다.
 *
 * <p>메이투안 글의 과부하 기준이다. 사용자가 업무에 맞게 클러스터에 알람 정책을 걸어 두고, 재생하는 동안
 * 알람이 울리면 그 배속은 과부하다. 무엇을 알람으로 삼을지는 이 도구가 정하지 않는다.
 */
@FunctionalInterface
public interface Alarm {

	/** @return 울렸으면 그 이유. 안 울렸으면 null */
	String check(RoundData round) throws Exception;

	/**
	 * 사용자가 준 명령으로 확인한다. 이미 쓰고 있는 알람 체계에 그 구간에 알람이 있었는지 묻는 명령을 넣는다.
	 * 명령이 0으로 끝나면 알람이 없었던 것이고, 그 밖의 값으로 끝나면 울린 것이다.
	 *
	 * <p>명령은 환경 변수로 구간을 받는다. {@code DBREPLAY_ROUND_START}와 {@code DBREPLAY_ROUND_END}는 에포크
	 * 초이고 {@code DBREPLAY_SPEED}는 배속이다. 표준 출력의 첫 줄을 이유로 쓴다.
	 */
	static Alarm command(String command) {
		return round -> {
			ProcessBuilder builder = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true);
			Map<String, String> env = builder.environment();
			env.put("DBREPLAY_ROUND_START", Long.toString(round.startedMillis() / 1_000));
			env.put("DBREPLAY_ROUND_END", Long.toString(round.endedMillis() / 1_000 + 1));
			env.put("DBREPLAY_SPEED", Double.toString(round.speed()));
			Process process = builder.start();
			String firstLine;
			try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				firstLine = out.readLine();
				while (out.readLine() != null) {
					// 나머지는 버린다. 다 읽어야 명령이 끝난다
				}
			}
			catch (IOException e) {
				firstLine = null;
			}
			int exit = process.waitFor();
			if (exit == 0) {
				return null;
			}
			return firstLine == null || firstLine.isBlank() ? "알람 명령이 " + exit + "(으)로 끝났다" : firstLine.strip();
		};
	}

	/**
	 * 실행 중인 연결 수가 threshold 이상인 채로 sustain번 연속으로 떠지면 울린다.
	 * 알람 체계가 따로 없을 때 쓰는 내장 알람이다. 임계값은 사용자가 정한다.
	 */
	static Alarm activeSessions(int threshold, int sustain) {
		return round -> {
			int run = 0;
			for (int v : round.activeSessions()) {
				run = v >= threshold ? run + 1 : 0;
				if (run >= sustain) {
					return "실행 중인 연결이 " + sustain + "번 연속 " + threshold + " 이상 (최대 " + round.maxActiveSessions() + ")";
				}
			}
			return null;
		};
	}
}
