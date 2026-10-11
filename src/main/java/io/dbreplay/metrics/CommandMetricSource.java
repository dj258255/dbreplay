package io.dbreplay.metrics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 사용자가 준 명령의 출력에서 지표를 읽는다. CPU와 Load Average처럼 DB 접속으로 못 읽는 것을 받는 자리다.
 *
 * <p>명령은 한 줄에 지표 하나를 {@code 이름 값}이나 {@code 이름=값}으로 낸다. 숫자로 읽히지 않는 줄은 버린다.
 * 메이투안의 시스템은 장비에 수집 에이전트를 둔다. 이 도구는 프로세스 하나로 끝나야 해서 명령으로 받는다.
 */
public final class CommandMetricSource implements MetricSource {

	private final String command;

	public CommandMetricSource(String command) {
		this.command = command;
	}

	@Override
	public Map<String, Double> read() throws IOException, InterruptedException {
		Process process = new ProcessBuilder("sh", "-c", command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		Map<String, Double> values = new LinkedHashMap<>();
		try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			for (String line; (line = out.readLine()) != null;) {
				String[] parts = line.strip().split("[=\\s]+", 2);
				if (parts.length == 2) {
					try {
						values.put(parts[0], Double.valueOf(parts[1].strip()));
					}
					catch (NumberFormatException notANumber) {
						// 지표가 아닌 줄
					}
				}
			}
		}
		process.waitFor();
		return values;
	}
}
