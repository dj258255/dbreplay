package io.dbreplay.replay;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JdbcTargetTest {

	private static boolean multi(String url) {
		return new JdbcTarget(url, "u", "p").multiHost();
	}

	/** 여러 서버에 나눠 붙는 URL을 가린다. 실험 20에서 이런 URL로 뜬 qps가 초당 수백만이었다 (#108). */
	@Test
	void recognisesUrlsThatSpreadAcrossServers() {
		assertTrue(multi("jdbc:mysql:loadbalance://r1:3306,r2:3306/sbtest?loadBalanceAutoCommitStatementThreshold=1"));
		assertTrue(multi("jdbc:mysql:loadbalance://r1:3306/sbtest"), "호스트가 하나여도 드라이버가 나눠 붙는 경로다");
		assertTrue(multi("jdbc:mysql:replication://p:3306,r:3306/sbtest"));
		assertTrue(multi("jdbc:mysql://r1:3306,r2:3306/sbtest"));
		assertTrue(multi("jdbc:postgresql://p:5432,r:5432/shop?targetServerType=any"));
	}

	@Test
	void singleServerUrlsAreNotMultiHost() {
		assertFalse(multi("jdbc:mysql://mysql84:3306/sbtest?sslMode=DISABLED&allowPublicKeyRetrieval=true"));
		assertFalse(multi("jdbc:postgresql://pg16:5432/shop"));
		// 쉼표가 질의 문자열에만 있으면 호스트는 하나다
		assertFalse(multi("jdbc:mysql://m:3306/sbtest?sessionVariables=sql_mode='A,B'"));
		assertFalse(multi("jdbc:mysql://m:3306?a=1,2"));
	}
}
