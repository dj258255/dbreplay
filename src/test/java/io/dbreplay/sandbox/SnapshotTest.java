package io.dbreplay.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.dbreplay.replay.JdbcTarget;

class SnapshotTest {

	/** PostgreSQL은 pg_dump로 뜬다. reset이 같은 데이터베이스에 되부을 수 있게 --clean --if-exists를 준다. */
	@Test
	void buildsThePgDumpCommand() {
		List<String> tokens = Snapshot.command(true, false, new JdbcTarget.Endpoint("pg16", 5432, "postgres"), "u", "shop");

		assertEquals("pg_dump", tokens.get(0));
		assertTrue(tokens.contains("pg16"));
		assertTrue(tokens.contains("5432"));
		assertTrue(tokens.contains("u"));
		assertTrue(tokens.contains("shop"));
		assertTrue(tokens.contains("--clean"));
		assertTrue(tokens.contains("--if-exists"));
	}

	/** 오라클의 mysqldump에는 GTID 문장을 막는 옵션을 준다. 데이터베이스 이름이 인자의 맨 끝이다. */
	@Test
	void buildsTheMysqlCommandWithGtidPurgedOff() {
		List<String> tokens = Snapshot.command(false, false, new JdbcTarget.Endpoint("db", 3306, "sbtest"), "u", "sbtest");

		assertEquals("mysqldump", tokens.get(0));
		assertTrue(tokens.contains("--single-transaction"));
		assertTrue(tokens.contains("--no-tablespaces"));
		assertTrue(tokens.contains("--set-gtid-purged=OFF"));
		assertEquals("sbtest", tokens.get(tokens.size() - 1), "데이터베이스 이름이 맨 끝이다");
	}

	/** MariaDB의 mysqldump는 --set-gtid-purged를 모른다. 그 옵션만 빠지고 나머지는 같다. */
	@Test
	void leavesTheGtidOptionOutForTheMariaDbClient() {
		List<String> mariadb = Snapshot.command(false, true, new JdbcTarget.Endpoint("db", 3306, "sbtest"), "u", "sbtest");
		List<String> oracle = Snapshot.command(false, false, new JdbcTarget.Endpoint("db", 3306, "sbtest"), "u", "sbtest");

		assertFalse(mariadb.contains("--set-gtid-purged=OFF"));
		assertEquals(oracle.stream().filter(token -> !token.equals("--set-gtid-purged=OFF")).toList(), mariadb);
	}

	/** 암호는 명령의 인자로 넘기지 않는다. 프로세스 목록에 남기 때문이다. */
	@Test
	void neverPutsThePasswordInTheCommand() {
		Snapshot snapshot = new Snapshot(new JdbcTarget("jdbc:mysql://db:3306/sbtest", "u", "s3cret"), null);

		List<String> tokens = snapshot.command(false);

		assertFalse(tokens.contains("s3cret"), "암호는 MYSQL_PWD로 준다");
	}

	/** 암호는 환경 변수로 넘긴다. PostgreSQL은 PGPASSWORD, MySQL은 MYSQL_PWD이고 다른 기종의 이름은 없다. */
	@Test
	void passesThePasswordThroughTheEnvironment() {
		assertEquals(Map.of("PGPASSWORD", "s3cret"), Snapshot.environment(true, "s3cret"));
		assertEquals(Map.of("MYSQL_PWD", "s3cret"), Snapshot.environment(false, "s3cret"));
		assertEquals(Map.of(), Snapshot.environment(true, null), "암호가 없으면 환경 변수에 넣지 않는다");
		assertEquals(Map.of(), Snapshot.environment(false, null), "암호가 없으면 환경 변수에 넣지 않는다");
	}

	/** MariaDB 클라이언트인지는 --version 출력의 글자로 가른다. 오라클 클라이언트는 이 말을 쓰지 않는다. */
	@Test
	void recognizesTheMariaDbClientByItsVersionText() {
		assertTrue(Snapshot
				.isMariaDbClient("mysqldump from 11.4.2-MariaDB, client 10.19 for debian-linux-gnu (aarch64)"));
		assertFalse(Snapshot
				.isMariaDbClient("mysqldump  Ver 8.4.11 for Linux on aarch64 (MySQL Community Server - GPL)"));
	}

	/** 데이터베이스를 URL에서도 인자에서도 알 수 없으면 덤프를 뜨기 전에 거른다. */
	@Test
	void refusesToDumpWithoutADatabase() {
		Snapshot snapshot = new Snapshot(new JdbcTarget("jdbc:mysql://db:3307/?x=y", "u", "p"), null);

		assertThrows(IllegalArgumentException.class, () -> snapshot.dump(Path.of("ignored.sql")));
	}

	private record Copied(String text, List<String> dropped) {
	}

	private static Copied copy(String input, Set<String> knownSettings) throws IOException {
		StringWriter out = new StringWriter();
		List<String> dropped = Snapshot.copyDroppingUnknownSettings(new BufferedReader(new StringReader(input)), out,
				knownSettings);
		return new Copied(out.toString(), dropped);
	}

	/** 머리에서 서버가 모르는 설정 줄만 빼고 나머지는 순서대로 남긴다. */
	@Test
	void dropsOnlyTheSettingsTheServerDoesNotKnow() throws IOException {
		String input = "--\n-- PostgreSQL database dump\n--\n\n"
				+ "SET statement_timeout = 0;\n"
				+ "SET transaction_timeout = 0;\n"
				+ "SET client_encoding = 'UTF8';\n\n"
				+ "CREATE TABLE t (id int);\n";
		Copied copied = copy(input, Set.of("statement_timeout", "client_encoding"));

		assertEquals(List.of("SET transaction_timeout = 0;"), copied.dropped());
		assertEquals("--\n-- PostgreSQL database dump\n--\n\n"
				+ "SET statement_timeout = 0;\n"
				+ "SET client_encoding = 'UTF8';\n\n"
				+ "CREATE TABLE t (id int);\n", copied.text());
	}

	/** 머리가 끝난 뒤의 같은 글자는 테이블 데이터일 수 있어 건드리지 않는다. */
	@Test
	void leavesSetLinesAfterTheHeaderAlone() throws IOException {
		String input = "CREATE TABLE t (id int);\nSET transaction_timeout = 0;\n";
		Copied copied = copy(input, Set.of());

		assertEquals(List.of(), copied.dropped());
		assertEquals(input, copied.text());
	}

	/** 서버가 다 아는 설정이면 입력과 출력이 같고 뺀 줄이 없다. */
	@Test
	void keepsEverythingWhenTheServerKnowsAllSettings() throws IOException {
		String input = "SET statement_timeout = 0;\nSET client_encoding = 'UTF8';\n";
		Copied copied = copy(input, Set.of("statement_timeout", "client_encoding"));

		assertEquals(List.of(), copied.dropped());
		assertEquals(input, copied.text());
	}

	/** SET SESSION AUTHORIZATION, set_config, psql 메타 명령은 남고 머리를 끝내지도 않는다. 그 뒤의 모르는 SET은 여전히 빠진다. */
	@Test
	void keepsOtherHeaderLinesAndStaysInTheHeader() throws IOException {
		String input = "SET SESSION AUTHORIZATION 'x';\n"
				+ "SELECT pg_catalog.set_config('search_path', '', false);\n"
				+ "\\restrict abc\n"
				+ "SET transaction_timeout = 0;\n"
				+ "CREATE TABLE t (id int);\n";
		Copied copied = copy(input, Set.of());

		assertEquals(List.of("SET transaction_timeout = 0;"), copied.dropped());
		assertEquals("SET SESSION AUTHORIZATION 'x';\n"
				+ "SELECT pg_catalog.set_config('search_path', '', false);\n"
				+ "\\restrict abc\n"
				+ "CREATE TABLE t (id int);\n", copied.text());
	}

	/** 바이트를 글자 하나로 그대로 옮긴다. 덤프의 인코딩이 무엇이든 데이터가 깨지지 않는다. */
	@Test
	void copiesBytesWithoutChangingTheEncoding() throws IOException {
		String korean = new String("한글 데이터".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
		String input = korean + "\n";
		Copied copied = copy(input, Set.of());

		assertEquals(List.of(), copied.dropped());
		assertEquals(input, copied.text());
	}
}