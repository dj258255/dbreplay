package io.dbreplay.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class MainTest {

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
