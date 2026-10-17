package io.dbreplay.serve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.dbreplay.explore.Evaluation;
import io.dbreplay.explore.TrafficModel;

class ServerTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String TOKEN = "0123456789abcdef0123";
	private final HttpClient client = HttpClient.newHttpClient();

	private HttpResponse<String> get(Server server, String path) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
				.header("Authorization", "Bearer " + TOKEN).build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(Server server, String path, String body, String token, String type) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
				.POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", type);
		if (token != null) {
			b.header("Authorization", "Bearer " + token);
		}
		return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(Server server, String path, String body) throws Exception {
		return post(server, path, body, TOKEN, "application/json");
	}

	/**
	 * 화면과 API가 평가를 보여 주고, 재생을 맡으면 한 번에 하나만 돌리며 끝난 요약을 돌려준다 (#88). API는 토큰을 요구하고
	 * 재생 대상은 대상 목록의 이름으로만 받는다(보안 검토의 지적).
	 */
	@Test
	void showsEvaluationsAndRunsOneReplayAtATime(@TempDir Path dir) throws Exception {
		Evaluation e = new Evaluation("2026-10-15T00:00:00Z", 30, Map.of(), Map.of(), "transaction",
				new TrafficModel(1_000, 10_000_000, Map.of(), 800, 200), 5, "OVERLOADED", 500, 200, 0.3, 0.5, List.of(5.0, 7.0));
		JSON.writeValue(dir.resolve("shop.json").toFile(), e);
		Files.writeString(dir.resolve("before-after.md"), "# 비교\n<script>x</script>");
		Files.writeString(dir.resolve("prod.trace.jsonl"), "");
		Path targets = dir.resolve("targets.list");
		Files.writeString(targets, "{\"targets\": [{\"name\": \"shop\", \"evaluation\": \"shop.json\","
				+ " \"options\": {\"--url\": \"jdbc:mysql://db/shop\", \"--user\": \"app\"}}]}");
		CountDownLatch release = new CountDownLatch(1);
		List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
		Server server = new Server(dir, targets, "127.0.0.1", 0, TOKEN, (trace, url, user, speed, unit) -> {
			seen.add(url + " " + user);
			release.await();
			return Map.of("statements", 10, "trace", trace.getFileName().toString());
		});
		server.start();
		try {
			HttpResponse<String> index = get(server, "/");
			assertEquals(200, index.statusCode());
			assertTrue(index.body().contains("shop"));
			assertTrue(index.body().contains("before-after"));

			HttpResponse<String> report = get(server, "/reports/before-after");
			assertFalse(report.body().contains("<script>x"), "리포트의 글자는 이스케이프한다");

			assertEquals("[\"shop\"]", get(server, "/api/evaluations").body());
			assertEquals(5.0, JSON.readTree(get(server, "/api/evaluations/shop").body()).get("ceilingSpeed").asDouble());
			assertEquals(400, get(server, "/api/evaluations/..%2Fsecret").statusCode(), "작업 폴더 밖으로 나가지 않는다");
			assertTrue(get(server, "/api/openapi.json").body().contains("\"openapi\""));

			String request = "{\"trace\":\"prod.trace.jsonl\",\"target\":\"shop\",\"speed\":2}";
			assertEquals(401, post(server, "/api/replays", request, null, "application/json").statusCode(), "토큰이 없으면 받지 않는다");
			assertEquals(401, post(server, "/api/replays", request, "wrong-token-wrong-token", "application/json").statusCode());
			assertEquals(415, post(server, "/api/replays", request, TOKEN, "text/plain").statusCode(), "다른 사이트의 단순 요청을 막는다");
			assertEquals(400, post(server, "/api/replays", "{\"trace\":\"prod.trace.jsonl\",\"target\":\"other\"}").statusCode(),
					"대상 목록에 없는 이름은 받지 않는다");
			assertEquals(400, post(server, "/api/replays", "{\"trace\":\"prod.trace.jsonl\",\"url\":\"jdbc:mysql://evil/x\"}").statusCode(),
					"URL을 받지 않는다");
			HttpResponse<String> started = post(server, "/api/replays", request);
			assertEquals(202, started.statusCode());
			assertEquals(409, post(server, "/api/replays", request).statusCode(), "하나가 도는 동안 다른 재생은 받지 않는다");
			long id = JSON.readTree(started.body()).get("id").asLong();
			assertEquals("running", JSON.readTree(get(server, "/api/replays/" + id).body()).get("state").asText());

			release.countDown();
			String state = "running";
			for (int i = 0; i < 100 && state.equals("running"); i++) {
				Thread.sleep(20);
				state = JSON.readTree(get(server, "/api/replays/" + id).body()).get("state").asText();
			}
			assertEquals("done", state);
			assertEquals(10, JSON.readTree(get(server, "/api/replays/" + id).body()).get("summary").get("statements").asInt());
			assertEquals(List.of("jdbc:mysql://db/shop app"), seen, "URL과 계정은 서버가 대상 목록에서 찾는다");
		}
		finally {
			server.stop();
		}
	}
}
