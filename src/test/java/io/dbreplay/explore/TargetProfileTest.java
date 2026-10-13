package io.dbreplay.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TargetProfileTest {

	/** 한 줄에 이름과 값 하나. 이름 뒤 처음 공백이나 =에서 가르고 값의 공백은 보존한다. 빈 줄, 주석, 값 없는 줄은 버린다. */
	@Test
	void parsesNamesAndValuesFromTheCommandsOutput() throws IOException {
		Map<String, String> host = TargetProfile.parseHost(new BufferedReader(
				new StringReader("cpus 8\nmemory_gb=16\nmodel Apple M3 Pro\n\n# 주석\nnovalue\n")));

		assertEquals(Map.of("host.cpus", "8", "host.memory_gb", "16", "host.model", "Apple M3 Pro"), host);
	}

	/** 같은 이름이 두 번 나오면 뒤의 값이 남는다. */
	@Test
	void keepsTheLastValueForARepeatedName() throws IOException {
		Map<String, String> host = TargetProfile
				.parseHost(new BufferedReader(new StringReader("cpus 8\ncpus 4\n")));

		assertEquals(Map.of("host.cpus", "4"), host);
	}
}