package io.dbreplay.serve;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.dbreplay.explore.CeilingRange;
import io.dbreplay.explore.Evaluation;
import io.dbreplay.explore.ManagedTargets;
import io.dbreplay.explore.WaterLevel;

/**
 * 작업 폴더의 평가와 리포트를 보여 주는 화면과, 다른 서비스가 재생을 맡기는 HTTP API(원문 대조 6, 7, #88).
 * 글에는 재생, 탐색, 운영, 예산 보정의 화면이 있고 다른 서비스가 OpenAPI로 재생을 쓴다.
 *
 * <p>의존성을 더하지 않으려고 JDK에 든 HTTP 서버와 서버가 그리는 HTML을 쓴다. 기본은 이 장비({@code 127.0.0.1})에서만 열린다.
 * 재생은 한 번에 하나만 받는다. 같은 대상에 재생 둘이 겹치면 둘 다 측정으로 쓸 수 없다.
 *
 * <p>API로 DB에 붙게 하므로 닫힌 쪽으로 둔다(커밋 전 보안 검토의 지적).
 * <ul>
 * <li>재생 대상은 대상 목록에 등록된 이름으로만 받는다. URL과 계정은 서버가 목록에서 찾는다. 요청에서 JDBC URL을 받으면 아무 주소로나
 *     붙게 할 수 있고 드라이버 속성을 끼워 넣을 수 있다. 암호는 서버를 띄울 때의 환경 변수({@code DBREPLAY_PASSWORD})다
 * <li>모든 {@code /api/}는 서버를 띄울 때 정한 토큰을 {@code Authorization: Bearer}로 요구한다
 * <li>Host 머리가 서버가 연 주소가 아니면 거절한다(DNS 리바인딩). POST는 Content-Type이 JSON이어야 한다(다른 사이트의 단순 요청)
 * </ul>
 */
public final class Server {

	/** 재생 하나를 실제로 돌리는 곳. Main이 넣는다. 테스트에서는 가짜를 넣는다. */
	@FunctionalInterface
	public interface ReplayRunner {
		/** @return 재생 요약(JSON으로 낼 값) */
		Map<String, Object> run(Path trace, String url, String user, double speed, String unit) throws Exception;
	}

	/** 재생 요청. target은 대상 목록의 이름이다. */
	public record ReplayRequest(String trace, String target, Double speed, String unit) {
	}

	/** 맡은 재생의 상태. */
	public record ReplayState(long id, String state, Map<String, Object> summary, String error) {
	}

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");

	private final Path dir;
	private final Path targets;
	private final ReplayRunner runner;
	private final byte[] token;
	private final java.util.Set<String> hosts;
	private final HttpServer http;
	private final AtomicBoolean busy = new AtomicBoolean();
	private final AtomicLong ids = new AtomicLong();
	private final Map<Long, ReplayState> replays = new ConcurrentHashMap<>();

	/**
	 * @param dir     평가 파일(*.json), 리포트(*.md), 트래픽 파일이 있는 작업 폴더. API는 이 폴더 밖의 파일을 읽지 않는다
	 * @param targets {@code manage}의 대상 목록. 없으면 null
	 * @param host    여는 주소. 기본은 127.0.0.1이다
	 * @param token   {@code /api/}에 요구하는 토큰
	 */
	public Server(Path dir, Path targets, String host, int port, String token, ReplayRunner runner) throws IOException {
		if (token == null || token.length() < 16) {
			throw new IllegalArgumentException("API 토큰은 16글자 이상이어야 한다");
		}
		this.dir = dir.toAbsolutePath().normalize();
		this.targets = targets;
		this.runner = runner;
		this.token = token.getBytes(StandardCharsets.UTF_8);
		this.http = HttpServer.create(new InetSocketAddress(host, port), 0);
		int bound = http.getAddress().getPort();
		this.hosts = host.equals("127.0.0.1") || host.equals("localhost")
				? java.util.Set.of("127.0.0.1:" + bound, "localhost:" + bound)
				: java.util.Set.of(host + ":" + bound);
		http.createContext("/", this::handle);
	}

	public void start() {
		http.start();
	}

	public int port() {
		return http.getAddress().getPort();
	}

	public void stop() {
		http.stop(0);
	}

	private void handle(HttpExchange ex) throws IOException {
		try {
			String path = ex.getRequestURI().getPath();
			String method = ex.getRequestMethod();
			if (!hosts.contains(String.valueOf(ex.getRequestHeaders().getFirst("Host")))) {
				json(ex, 421, Map.of("error", "이 서버의 주소로 온 요청이 아니다"));
				return;
			}
			if (path.startsWith("/api/") && !path.equals("/api/openapi.json") && !authorized(ex)) {
				json(ex, 401, Map.of("error", "Authorization: Bearer <토큰>이 필요하다"));
				return;
			}
			if (method.equals("POST") && !String.valueOf(ex.getRequestHeaders().getFirst("Content-Type")).startsWith("application/json")) {
				json(ex, 415, Map.of("error", "Content-Type은 application/json이어야 한다"));
				return;
			}
			if (method.equals("GET") && path.equals("/")) {
				html(ex, 200, Pages.index(evaluations(), targetRows(), reports()));
			}
			else if (method.equals("GET") && path.startsWith("/evaluations/")) {
				String name = name(path.substring("/evaluations/".length()));
				html(ex, 200, Pages.evaluation(name, readEvaluation(name)));
			}
			else if (method.equals("GET") && path.startsWith("/reports/")) {
				String name = name(path.substring("/reports/".length()));
				html(ex, 200, Pages.report(name, Files.readString(inside(name, ".md"), StandardCharsets.UTF_8)));
			}
			else if (method.equals("GET") && path.equals("/api/openapi.json")) {
				send(ex, 200, "application/json", OpenApi.DOCUMENT.getBytes(StandardCharsets.UTF_8));
			}
			else if (method.equals("GET") && path.equals("/api/evaluations")) {
				json(ex, 200, evaluations().keySet());
			}
			else if (method.equals("GET") && path.startsWith("/api/evaluations/")) {
				json(ex, 200, readEvaluation(name(path.substring("/api/evaluations/".length()))));
			}
			else if (method.equals("GET") && path.equals("/api/targets")) {
				json(ex, 200, targetRows());
			}
			else if (method.equals("POST") && path.equals("/api/replays")) {
				startReplay(ex);
			}
			else if (method.equals("GET") && path.startsWith("/api/replays/")) {
				ReplayState state = replays.get(Long.parseLong(path.substring("/api/replays/".length())));
				if (state == null) {
					json(ex, 404, Map.of("error", "그런 재생이 없다"));
				}
				else {
					json(ex, 200, state);
				}
			}
			else {
				json(ex, 404, Map.of("error", "없는 주소다"));
			}
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			// 모르는 필드(예: url)나 깨진 JSON. 요청이 잘못된 것이다
			json(ex, 400, Map.of("error", "요청 본문을 읽을 수 없다. trace, target, speed, unit만 받는다"));
		}
		catch (IllegalArgumentException | java.nio.file.NoSuchFileException e) {
			json(ex, 400, Map.of("error", e instanceof java.nio.file.NoSuchFileException ? "그런 파일이 없다" : e.getMessage()));
		}
		catch (Exception e) {
			// 메시지에 접속 정보가 섞일 수 있다. 종류만 낸다
			json(ex, 500, Map.of("error", e.getClass().getSimpleName()));
		}
		finally {
			ex.close();
		}
	}

	/** 토큰을 같은 시간에 비교한다. */
	private boolean authorized(HttpExchange ex) {
		String header = ex.getRequestHeaders().getFirst("Authorization");
		if (header == null || !header.startsWith("Bearer ")) {
			return false;
		}
		return java.security.MessageDigest.isEqual(token, header.substring(7).strip().getBytes(StandardCharsets.UTF_8));
	}

	private void startReplay(HttpExchange ex) throws IOException {
		ReplayRequest request;
		try (InputStream body = ex.getRequestBody()) {
			request = JSON.readValue(body, ReplayRequest.class);
		}
		if (request.trace() == null || request.target() == null) {
			throw new IllegalArgumentException("trace와 target(대상 목록의 이름)이 있어야 한다");
		}
		ManagedTargets.Target target = registered(request.target());
		String url = target.options().get("--url");
		String user = target.options().get("--user");
		if (url == null || user == null) {
			throw new IllegalArgumentException("그 대상의 옵션에 --url과 --user가 없다");
		}
		Path trace = inside(name(request.trace()), "");
		double speed = request.speed() == null ? 1 : request.speed();
		String unit = request.unit() == null ? "transaction" : request.unit();
		if (!busy.compareAndSet(false, true)) {
			json(ex, 409, Map.of("error", "다른 재생이 돌고 있다. 끝난 뒤에 다시 보낸다"));
			return;
		}
		long id = ids.incrementAndGet();
		replays.put(id, new ReplayState(id, "running", null, null));
		Thread.ofPlatform().name("dbreplay-api-replay").start(() -> {
			try {
				replays.put(id, new ReplayState(id, "done", runner.run(trace, url, user, speed, unit), null));
			}
			catch (Exception e) {
				replays.put(id, new ReplayState(id, "failed", null, e.getClass().getSimpleName()));
			}
			finally {
				busy.set(false);
			}
		});
		json(ex, 202, Map.of("id", id, "status", "/api/replays/" + id));
	}

	/** 작업 폴더의 평가 파일들. 이름은 파일 이름에서 .json을 뗀 것이다. 평가 파일이 아닌 JSON은 건너뛴다. */
	Map<String, Evaluation> evaluations() throws IOException {
		Map<String, Evaluation> out = new java.util.TreeMap<>();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
				try {
					out.put(f.getFileName().toString().replaceFirst("\\.json$", ""), JSON.readValue(f.toFile(), Evaluation.class));
				}
				catch (IOException notAnEvaluation) {
					// 대상 목록이나 재생 기록 같은 다른 JSON이다
				}
			}
		}
		return out;
	}

	/** 작업 폴더의 리포트(*.md). 이름은 .md를 뗀 것이다. */
	List<String> reports() throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".md") && NAME.matcher(n).matches())
					.map(n -> n.substring(0, n.length() - 3)).sorted().toList();
		}
	}

	private Evaluation readEvaluation(String name) throws IOException {
		return JSON.readValue(inside(name, ".json").toFile(), Evaluation.class);
	}

	/**
	 * 대상 목록의 한 줄씩: 이름, 수위, 판정, 상한, 권고 노드 수와 예산의 변화(예산 보정). 대상 옵션에 {@code --read-nodes}와
	 * {@code --node-cost}(노드 하나의 비용)가 있어야 예산을 낸다. 권고는 explore와 같은 가정(읽기 상한이 노드 수에 비례한다)이다.
	 */
	List<Map<String, Object>> targetRows() throws IOException {
		if (targets == null) {
			return List.of();
		}
		ManagedTargets managed = JSON.readValue(targets.toFile(), ManagedTargets.class);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (ManagedTargets.Target t : managed.targets()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", t.name());
			Path file = Path.of(t.evaluation());
			if (!file.isAbsolute()) {
				file = targets.toAbsolutePath().getParent().resolve(file);
			}
			if (!Files.exists(file)) {
				row.put("state", "평가한 적이 없다");
				rows.add(row);
				continue;
			}
			Evaluation e = JSON.readValue(file.toFile(), Evaluation.class);
			WaterLevel level = e.level();
			row.put("evaluatedAt", e.evaluatedAt());
			row.put("ceilingSpeed", e.ceilingSpeed());
			row.put("level", level.level());
			row.put("verdict", level.verdict().name());
			CeilingRange range = e.ceilingRange();
			if (range != null) {
				row.put("ceilingRange", List.of(range.min(), range.max()));
			}
			Budget.of(e, t.options()).ifPresent(b -> row.put("budget", b));
			rows.add(row);
		}
		return rows;
	}

	/** 대상 목록에서 이름으로 찾는다. 목록이 없거나 그런 이름이 없으면 받지 않는다. */
	private ManagedTargets.Target registered(String name) throws IOException {
		if (targets == null) {
			throw new IllegalArgumentException("대상 목록(--targets) 없이 띄운 서버는 재생을 받지 않는다");
		}
		return JSON.readValue(targets.toFile(), ManagedTargets.class).targets().stream().filter(t -> t.name().equals(name)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("대상 목록에 없는 이름이다"));
	}

	private static String name(String raw) {
		String decoded = java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
		if (!NAME.matcher(decoded).matches()) {
			throw new IllegalArgumentException("이름에는 영문, 숫자, 점, 밑줄, 붙임표만 쓴다");
		}
		return decoded;
	}

	/** 작업 폴더 안의 파일. 밖으로 나가는 이름은 거른다. */
	private Path inside(String name, String suffix) {
		Path p = dir.resolve(name + suffix).normalize();
		if (!p.startsWith(dir)) {
			throw new IllegalArgumentException("작업 폴더 밖의 파일이다");
		}
		return p;
	}

	private static void html(HttpExchange ex, int status, String body) throws IOException {
		send(ex, status, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
	}

	private static void json(HttpExchange ex, int status, Object value) throws IOException {
		send(ex, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(value));
	}

	private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
		ex.getResponseHeaders().set("Content-Type", type);
		ex.sendResponseHeaders(status, body.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(body);
		}
	}
}
