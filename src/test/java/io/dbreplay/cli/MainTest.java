package io.dbreplay.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.dbreplay.explore.Evaluation;
import io.dbreplay.explore.TrafficModel;

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
}
