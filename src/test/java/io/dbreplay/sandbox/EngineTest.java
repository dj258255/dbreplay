package io.dbreplay.sandbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class EngineTest {

	/** 서버가 알려 준 버전에서 이미지 태그를 뽑는다 (#84). */
	@Test
	void picksTheImageTagFromTheServerVersion() {
		assertEquals("8.4.11", Engine.tag("8.4.11"));
		assertEquals("8.0.46", Engine.tag("8.0.46-log"));
		assertEquals("16.4", Engine.tag("16.4 (Debian 16.4-1.pgdg120+2)"));
		assertThrows(IllegalArgumentException.class, () -> Engine.tag("MariaDB"));
	}

	/** 장비와 경로, 서버의 정체성에 매인 파라미터는 옮기지 않는다. */
	@Test
	void excludesHostBoundParameters() {
		assertTrue(Engine.MYSQL.excluded("server_uuid"));
		assertTrue(Engine.MYSQL.excluded("datadir"));
		assertTrue(Engine.MYSQL.excluded("ssl_ca"));
		assertFalse(Engine.MYSQL.excluded("innodb_flush_log_at_trx_commit"));
		assertTrue(Engine.POSTGRES.excluded("data_directory"));
		assertFalse(Engine.POSTGRES.excluded("shared_buffers"));
	}

	/** 숫자와 ON/OFF는 그대로, 글자는 따옴표로. 이름이 이상하면 문장을 만들지 않는다. */
	@Test
	void writesPersistentSettings() {
		assertEquals("SET PERSIST_ONLY innodb_flush_log_at_trx_commit = 2", Engine.MYSQL.persist("innodb_flush_log_at_trx_commit", "2"));
		assertEquals("SET PERSIST_ONLY sql_mode = 'STRICT_TRANS_TABLES'", Engine.MYSQL.persist("sql_mode", "STRICT_TRANS_TABLES"));
		assertEquals("SET PERSIST_ONLY slow_query_log = ON", Engine.MYSQL.persist("slow_query_log", "ON"));
		assertEquals("ALTER SYSTEM SET work_mem = '4096'", Engine.POSTGRES.persist("work_mem", "4096"));
		assertEquals("ALTER SYSTEM SET x = 'it''s'", Engine.POSTGRES.persist("x", "it's"));
		assertThrows(IllegalArgumentException.class, () -> Engine.MYSQL.persist("a; DROP TABLE t", "1"));
	}

	@Test
	void diffsBothWays() {
		Map<String, String[]> diff = Engine.diff(Map.of("a", "1", "b", "2", "c", "3"), Map.of("a", "1", "b", "9", "d", "4"));
		assertEquals(3, diff.size());
		assertArrayEquals(new String[] {"2", "9"}, diff.get("b"));
		assertArrayEquals(new String[] {"3", null}, diff.get("c"));
		assertArrayEquals(new String[] {null, "4"}, diff.get("d"));
	}

	/** 호스트와 포트만 바꾸고 접속 옵션은 지킨다. 관리 작업에는 데이터베이스 자리를 바꾼다. */
	@Test
	void rewritesTheUrlForTheSandbox() {
		assertEquals("jdbc:mysql://127.0.0.1:3307/shop?sslMode=DISABLED",
				DockerSandbox.sandboxUrl("jdbc:mysql://db.prod:3306/shop?sslMode=DISABLED", 3307, null));
		assertEquals("jdbc:mysql://127.0.0.1:3307/?sslMode=DISABLED",
				DockerSandbox.sandboxUrl("jdbc:mysql://db.prod:3306/shop?sslMode=DISABLED", 3307, "/"));
		assertEquals("jdbc:postgresql://127.0.0.1:5433/postgres",
				DockerSandbox.sandboxUrl("jdbc:postgresql://db.prod/shop", 5433, "/postgres"));
	}
}
