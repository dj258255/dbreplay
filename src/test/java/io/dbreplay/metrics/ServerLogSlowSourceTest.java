package io.dbreplay.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerLogSlowSourceTest {

	private static void append(Path file, String text) throws Exception {
		Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
	}

	private static double total(ServerLogSlowSource source) throws Exception {
		return source.read().get("slow_finished_total");
	}

	/** 시작한 뒤에 붙은 줄만, 기준을 넘긴 것만 센다. 줄 중간에서 끊긴 끝은 다음에 센다. 로그를 돌리면 처음부터 읽는다 (#82). */
	@Test
	void countsSlowStatementsAppendedAfterStart(@TempDir Path dir) throws Exception {
		Path log = dir.resolve("postgresql.csv");
		Files.writeString(log, "\"...duration: 500.000 ms  statement: SELECT pg_sleep(0.5)\"\n");
		ServerLogSlowSource source = new ServerLogSlowSource(log, 100);
		assertEquals(0.0, total(source), "시작하기 전의 줄은 세지 않는다");

		append(log, "\"...duration: 150.100 ms  statement: SELECT 1\"\n\"...duration: 100.000 ms  statement: SELECT 2\"\n");
		assertEquals(1.0, total(source), "기준과 같은 것은 넘긴 것이 아니다");

		append(log, "\"...duration: 2");
		assertEquals(1.0, total(source));
		append(log, "00.000 ms  statement: SELECT '한글'\"\n");
		assertEquals(2.0, total(source), "끊긴 줄은 이어 붙여 센다");

		Files.writeString(log, "\"...duration: 300.000 ms  statement: SELECT 3\"\n");
		assertEquals(3.0, total(source), "파일이 줄었으면 처음부터 다시 읽고 누적은 잇는다");
	}
}
