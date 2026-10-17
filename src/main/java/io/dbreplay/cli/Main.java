package io.dbreplay.cli;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.dbreplay.convert.ConvertSummary;
import io.dbreplay.convert.HeadPolicy;
import io.dbreplay.convert.LiteralMasker;
import io.dbreplay.convert.RecordBuffer;
import io.dbreplay.convert.ScopeFilter;
import io.dbreplay.explore.Alarm;
import io.dbreplay.explore.CeilingRange;
import io.dbreplay.explore.Evaluation;
import io.dbreplay.explore.Explorer;
import io.dbreplay.explore.ManagedTargets;
import io.dbreplay.explore.RoundData;
import io.dbreplay.explore.RoundJudge;
import io.dbreplay.explore.ScaleAdvice;
import io.dbreplay.explore.ScaleRun;
import io.dbreplay.explore.ZonePlan;
import io.dbreplay.explore.TargetProfile;
import io.dbreplay.explore.TrafficModel;
import io.dbreplay.explore.WaterLevel;
import io.dbreplay.metrics.CommandMetricSource;
import io.dbreplay.metrics.DbMetricSource;
import io.dbreplay.metrics.MetricSampler;
import io.dbreplay.metrics.PrefixedMetricSource;
import io.dbreplay.metrics.ReplicationLagSource;
import io.dbreplay.metrics.ServerLogSlowSource;
import io.dbreplay.mysql.MysqlCapture;
import io.dbreplay.mysql.SlowLogConverter;
import io.dbreplay.postgres.CsvLogConverter;
import io.dbreplay.postgres.PostgresCapture;
import io.dbreplay.replay.JdbcTarget;
import io.dbreplay.replay.ReplaySummary;
import io.dbreplay.replay.Replayer;
import io.dbreplay.replay.ResultWriter;
import io.dbreplay.replay.TraceSelection;
import io.dbreplay.report.MarkdownReport;
import io.dbreplay.report.NewErrors;
import io.dbreplay.report.RunRecord;
import io.dbreplay.report.TraceComparison;
import io.dbreplay.sandbox.Sandbox;
import io.dbreplay.sandbox.Snapshot;
import io.dbreplay.trace.PrivateFile;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;

public final class Main {

	private static final String USAGE = """
			사용법:
			  dbreplay convert --in <서버 로그> --out <트래픽 파일> [--from mysql-slow|postgres-csv|mysql-pcap|postgres-pcap]
			                   [--port <DB 포트>]
			                   mysql-pcap, postgres-pcap은 tcpdump -w로 뜬 패킷 캡처를 읽는다. 서버 로그를 켜지 않는다.
			                   TLS 연결과 캡처 전부터 열려 있던 연결은 읽지 못하고, 놓친 수를 알린다
			                   [--sort-memory-mb <힙의 4분의 1>] [--tmp-dir <출력 파일의 폴더>] [--mask none|strings|all]
			                   [--database <이름>,...] [--exclude-user <계정>,...]
			                   --database는 그 데이터베이스의 문장만, --exclude-user는 그 계정의 연결을 빼고 남긴다.
			                   메모리에 다 들어가지 않는 로그는 임시 파일로 나눠 정렬한다. 로그만큼의 디스크가 더 든다
			                   --mask strings는 글자로 된 값과 10자리 이상의 숫자를, all은 숫자까지 전부 가린다.
			                   길이와 글자의 종류를 지키고 같은 값은 같은 값으로 바꾼다. 날짜 모양의 값은 가리지 않는다.
			                   all은 값들 사이의 관계를 지키지 못한다. 범위 조회의 폭이 달라져 재생이 다른 부하가 된다(실험 13)

			  dbreplay replay  --trace <트래픽 파일> --url <JDBC URL> --user <계정> [--password <암호>]
			                   [--speed 1] [--unit transaction|statement|session] [--max-connections 500]
			                   [--only reads] [--out <결과 파일>] [--run-out <재생 기록 파일>] [--metrics on] [지표 옵션]
			                   [--profile-command <명령>] [주종 옵션] [--shard <i>/<N> [--start-at <에포크 밀리초>]]
			                   --shard는 원본의 연결 번호로 N개로 나눈 것 가운데 i번째만 튼다. 장비 여러 대가 나눠 틀 때
			                   장비마다 같은 --start-at을 준다. 시각의 기준은 전체 트래픽의 첫 문장이다

			  dbreplay compare --before <트래픽 파일> --after <트래픽 파일> [--top 3] [--min-count 100] [--rows 15]

			  dbreplay report  --before <트래픽 파일> --after <트래픽 파일> --out <리포트.md> [--title <제목>]
			                   [--before-run <재생 기록>] [--after-run <재생 기록>]
			                   [--before-evaluation <평가 파일>] [--after-evaluation <평가 파일>]
			                   [--top 3] [--min-count 100]
			                   변경 전후의 SQL 비교, 부하 지표, 파라미터, 용량을 한 문서로 낸다

			  dbreplay explore --trace <트래픽 파일> --url <JDBC URL> --user <계정> [--password <암호>]
			                   (--snapshot <덤프 파일> [되돌리기 옵션] | --reset-command <데이터 되돌리기 명령>)
			                   [--alarm-command <알람 확인 명령>] [--alarm-metric <이름>=<값>,...]
			                   [--alarm-active-sessions <개수>] [--alarm-total <이름>=<값>,...] [--alarm-sustain 3] [--max-distortion 0.2]
			                   [지표 옵션] [--profile-command <명령>] [주종 옵션]
			                   [--unit transaction|statement|session] [--only reads|writes] [--workers 1] [--max-connections 500]
			                   [--start-speed 1] [--max-speed 64] [--precision 0.1] [--repeat 1]
			                   [--current-qps <지금의 초당 문장 수>] [--current-read-qps <읽기>] [--current-write-qps <쓰기>]
			                   [--current-replica-qps <종 DB의 지금의 초당 문장 수>]
			                   [--level-low 0.3] [--level-high 0.5]
			                   [--read-nodes <읽기를 받는 노드 수>] [--min-read-nodes 1] [--max-read-nodes <한도>]
			                   [--zones <존>=<노드 수>,... [--min-per-zone 1]] [--max-write-scale <주 DB를 키울 수 있는 배수>]
			                   [--scale-command <명령> --apply-scale yes [--scale-verify-command <명령>]
			                    [--scale-rollback-command <명령>] [--scale-log <기록 파일>]]
			                   [--out <평가 파일>] [--valid-days 30]

			  dbreplay check   --evaluation <평가 파일> --url <JDBC URL> --user <계정> [--password <암호>]
			                   [--trace <지금의 트래픽 파일>] [--max-traffic-distance 0.2]
			                   [--current-qps <지금의 초당 문장 수>] [--current-replica-qps <종 DB>] [--profile-command <명령>] [explore에 줬던 알람 옵션]
			                   [--measure-url <운영 JDBC URL> [--measure-user <계정>] [--measure-seconds 60]]
			                   --measure-url을 주면 운영 DB에서 그 시간 동안 초당 문장 수를 재서 수위를 다시 계산한다.
			                   암호는 DBREPLAY_MEASURE_PASSWORD로 준다(없으면 --password와 같다). PostgreSQL은 pg_stat_statements가 있어야 한다

			  dbreplay manage  --targets <대상 목록.json> [--force yes] [--max-traffic-distance 0.2]
			                   등록한 대상을 훑어, 평가한 적이 없거나 만료됐거나 무효인 것만 다시 평가하고 수위를 한 표로 낸다.
			                   대상 목록은 {"targets": [{"name": ..., "evaluation": <평가 파일>, "options": {explore의 옵션}}]} 꼴이다.
			                   주기적으로 돌리려면 cron에 건다

			  dbreplay sample  --url <JDBC URL> --user <계정> [--password <암호>] --seconds <초> [지표 옵션]
			                   녹화하는 동안 운영 쪽의 부하 지표를 떠 둘 때 쓴다

			  dbreplay reset   --url <JDBC URL> --user <계정> [--password <암호>] --snapshot <덤프 파일> [되돌리기 옵션]
			                   샌드박스를 녹화 시작 시점으로 되돌린다. mysql이나 psql 클라이언트가 있어야 한다

			  dbreplay snapshot --url <JDBC URL> --user <계정> [--password <암호>] --out <덤프 파일> [--snapshot-db <이름>]
			                   녹화를 시작하기 직전에 뜬다. reset과 explore의 --snapshot에 그대로 준다. mysqldump나 pg_dump가 있어야 한다

			되돌리기 옵션:
			  --snapshot <덤프 파일>     녹화 시작 시점의 논리 덤프(mysqldump, pg_dump의 평문 출력)
			  --snapshot-db <이름>       덤프를 부을 데이터베이스. 없으면 URL의 것
			  --change-command <명령>    되부은 뒤 통계를 잡기 전에 실행한다. 검증하려는 변경을 여기서 넣는다
			  --ready-command <명령>     다 끝난 뒤 재생 직전에 실행한다
			  --settle-seconds 60        서버가 되부은 쓰기를 다 내릴 때까지 기다리는 시간의 상한. 0이면 기다리지 않는다

			지표 옵션:
			  --sample-ms 200            DB에서 지표를 뜨는 간격. active_sessions(실행 중인 연결)와
			                             slow_running(실행 중이면서 --slow-ms를 넘긴 문장)을 뜬다
			                             표본마다 DB가 내는 누적 값에서 qps(직전 표본부터의 초당 문장 수. PostgreSQL은
			                             pg_stat_statements가 있을 때만), tps(PostgreSQL만, 초당 트랜잭션 수),
			                             slow_finished_total(MySQL만, 표본을 뜨기 시작한 뒤 --slow-ms를 넘겨 끝난 문장의
			                             누적 수. 기준 시간이 든 칸까지 센다)도 뜬다
			  --slow-ms 100              실행 중인 문장을 느리다고 볼 기준 시간
			  --server-log <csvlog 경로> PostgreSQL의 서버 로그를 따라 읽어 slow_finished_total을 낸다.
			                             log_min_duration_statement가 --slow-ms 이하로 켜져 있어야 한다
			  --metric-command <명령>    DB 접속으로 못 읽는 지표(CPU, Load Average)를 받는다.
			                             한 줄에 '이름 값'을 내는 명령을 --metric-command-ms(기본 1000)마다 실행한다

			장비 설정(--profile-command):
			  한 줄에 '이름 값'을 내는 명령. CPU 수, 메모리, 기종 이름처럼 DB 접속으로 알 수 없는 것을 낸다.
			  평가 파일과 재생 기록에 host.을 붙여 남고, 바뀌면 평가가 무효다. 명령은 남기지 않고 출력만 남긴다

			주종 옵션:
			  --replica-url <JDBC URL>   주 DB(--url)를 복제로 따라오는 종 DB. 주면 복제 지연을 replication_lag_seconds로 뜨고
			                             (알람은 --alarm-metric 'replication_lag_seconds>=1'), 되돌린 뒤 종 DB가 따라올 때까지 기다린다.
			                             MySQL은 GTID가 켜져 있어야 한다. 계정이 다르면 --replica-user, --replica-password
			                             종 DB에서도 같은 지표를 replica.을 붙여 뜬다(replica.active_sessions 등). 종 DB에
			                             알람을 걸면(--alarm-metric 'replica.active_sessions>=32') 그 알람이 탐색을 멈췄을 때
			                             종 DB의 상한이 알람으로 확인된 것으로 본다
			  --replica-trace <파일>     종 DB에서 녹화한 트래픽. 주 DB의 재생과 같은 배속으로 종 DB에 함께 튼다.
			                             문장 하나씩 독립으로 나간다

			재생 단위(--unit):
			  transaction  트랜잭션 하나가 한 단위다. 주 DB의 트래픽에 쓴다 (기본)
			  statement    문장 하나가 한 단위다. 종 DB의 읽기 트래픽에 쓴다
			  session      원본의 연결을 그대로 옮긴다. 세션 상태에 기대는 트래픽에 쓴다
			--only reads를 주면 읽기 문장만 하나씩 독립으로 튼다. 읽기만 늘 때의 상한을 볼 때 쓴다
			--only writes를 주면 쓰기가 든 트랜잭션만 트랜잭션 단위로 튼다. 쓰기의 상한을 볼 때 쓴다
			--workers N을 주면 라운드마다 원본의 연결 번호로 나눈 재생기 N개를 나란히 돌린다. 재생기가 먼저 막힐 때 쓴다

			확장·축소 권고(--read-nodes):
			  읽기 수위가 건강 구간을 벗어나면 필요한 노드 수를 낸다. 읽기 상한이 노드 수에 비례한다는 가정이고 확인된 것이 아니다.
			  쓰기는 주 DB 하나가 받으므로 노드 수 대신 필요한 용량의 배수를 낸다.
			  --scale-command는 --apply-scale yes와 --max-read-nodes를 함께 줘야 실행된다. 권한 노드 수를 DBREPLAY_READ_NODES로 받는다
			  권한 수가 한도에 걸렸거나 상한이 알람으로 확인된 값이 아니면 실행하지 않는다. 명령이 실패하면 0이 아닌 값으로 끝난다
			  --zones a=2,b=1을 주면 권한 노드를 존에 나눈다. 가장 적은 존부터 채우고 가장 많은 존부터 빼며 존마다 --min-per-zone을 지킨다.
			  가장 많은 존 하나가 빠졌을 때 남은 노드의 수위를 함께 낸다. 존별 권고는 DBREPLAY_READ_NODES_BY_ZONE(a=3,b=2)로 받는다
			  --scale-verify-command는 확장 명령 뒤에 부른다. 둘 중 하나가 실패하면 --scale-rollback-command를 부른다.
			  되돌리기는 확장 전의 노드 수를 DBREPLAY_READ_NODES로 받는다. --scale-log에 단계마다 명령의 지문, 종료 코드, 걸린 시간을 쌓는다
			  --max-write-scale을 주면 쓰기에 필요한 용량이 그 배수를 넘을 때 몇 조각으로 나눌지 권한다

			되풀이 탐색(--repeat):
			  탐색을 N번 되풀이해 상한을 범위로 낸다(최소, 중앙값, 최대, 퍼짐). 수위와 평가 파일에는 가장 낮은 상한을 쓴다.
			  같은 절차의 상한이 실행마다 다르다(실험 12).

			과부하는 알람으로 판정한다. explore에는 알람이 하나는 있어야 한다.
			  --alarm-command          라운드가 끝날 때 sh -c로 실행한다. 0이 아닌 값으로 끝나면 알람이 울린 것이다.
			                           DBREPLAY_ROUND_START, DBREPLAY_ROUND_END(에포크 초), DBREPLAY_SPEED를 환경 변수로 받는다
			  --alarm-metric           지표가 값 이상으로 --alarm-sustain번 연속 떠지면 울린다.
			                           예: active_sessions>=32,load1>=8. 지표 명령이 내는 이름도 쓸 수 있다
			  --alarm-total            누적 지표(slow_finished_total)에 쓴다. 그 지표의 마지막 표본이 값 이상이면 울린다.
			                           예: slow_finished_total>=50. 누적 값은 한 번 넘으면 계속 넘어 있어 연속으로 보지 않는다
			  --alarm-active-sessions  --alarm-metric active_sessions>=<개수>와 같다

			암호는 --password 대신 환경 변수 DBREPLAY_PASSWORD로 줄 수 있다.
			compare의 두 파일은 재생할 때 대상의 로그를 켜서 얻은 것이어야 한다. 원본 로그와 재생 로그는 같은 자가 아니다.
			""";

	private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	private Main() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 0 || args.length % 2 == 0) {
			usage();
		}
		Map<String, String> options = new HashMap<>();
		for (int i = 1; i < args.length; i += 2) {
			options.put(args[i], args[i + 1]);
		}
		switch (args[0]) {
			case "convert" -> convert(options);
			case "replay" -> replay(options);
			case "compare" -> compare(options);
			case "explore" -> explore(options);
			case "report" -> report(options);
			case "check" -> check(options);
			case "sample" -> sample(options);
			case "reset" -> reset(options);
			case "snapshot" -> snapshot(options);
			case "manage" -> manage(options);
			default -> usage();
		}
	}

	private static void convert(Map<String, String> options) throws IOException {
		String from = options.getOrDefault("--from", "mysql-slow");
		Path out = Path.of(required(options, "--out")).toAbsolutePath();
		// 임시 파일은 출력 파일 옆에 둔다. 출력만큼의 자리가 더 있어야 하는 곳이 거기다. 시스템의 임시 폴더는 작을 수 있다
		Path tempParent = options.containsKey("--tmp-dir") ? Path.of(options.get("--tmp-dir")) : out.getParent();
		long sortBytes = options.containsKey("--sort-memory-mb") ? (long) (number(options, "--sort-memory-mb", 0) * 1024 * 1024)
				: Runtime.getRuntime().maxMemory() / 4;
		String mask = options.getOrDefault("--mask", "none");
		boolean mysql = from.startsWith("mysql");
		LiteralMasker masker = switch (mask) {
			case "none" -> null;
			case "strings" -> new LiteralMasker(LiteralMasker.Level.STRINGS, mysql, LiteralMasker.randomKey());
			case "all" -> new LiteralMasker(LiteralMasker.Level.ALL, mysql, LiteralMasker.randomKey());
			default -> throw new IllegalArgumentException("--mask에는 none, strings, all 중 하나를 준다");
		};
		String maskName = masker == null ? null : mask;
		ScopeFilter scope = new ScopeFilter(names(options.get("--database")), names(options.get("--exclude-user")));
		ConvertSummary s;
		long spills;
		// 트래픽 파일에는 값이 그대로 든 SQL이 들어 있다. 소유자만 읽을 수 있는 파일을 만들어 그 핸들에 바로
		// 쓴다. 경로로 다시 열면 그 사이에 경로가 심볼릭 링크로 바뀌어 다른 파일로 갈 수 있다.
		// 평가 파일·재생 결과·리포트는 값이 든 SQL이 없어 건드리지 않는다
		String losses = null;
		if (from.endsWith("-pcap")) {
			// 패킷 캡처. 서버 로그를 켜지 않고 녹화한 것이다(#79). 파일은 바이트로 읽는다
			int port = (int) number(options, "--port", mysql ? 3306 : 5432);
			try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(Path.of(required(options, "--in"))));
					Writer writer = new BufferedWriter(new OutputStreamWriter(PrivateFile.open(out), StandardCharsets.UTF_8));
					RecordBuffer buffer = new RecordBuffer(tempParent, sortBytes)) {
				switch (from) {
					case "mysql-pcap" -> {
						MysqlCapture capture = new MysqlCapture(in, port);
						s = SlowLogConverter.convert(capture, "pcap", writer, HeadPolicy::byDefault, buffer, masker, maskName, scope);
						losses = capture.losses();
					}
					case "postgres-pcap" -> {
						PostgresCapture capture = new PostgresCapture(in, port);
						s = CsvLogConverter.convert(capture, writer, HeadPolicy::byDefault, buffer, masker, maskName, scope);
						losses = capture.losses();
					}
					default -> throw new IllegalArgumentException("모르는 녹화 종류: " + from);
				}
				spills = buffer.spills();
			}
		}
		else {
			try (Reader reader = Files.newBufferedReader(Path.of(required(options, "--in")), StandardCharsets.UTF_8);
					Writer writer = new BufferedWriter(new OutputStreamWriter(PrivateFile.open(out), StandardCharsets.UTF_8));
					RecordBuffer buffer = new RecordBuffer(tempParent, sortBytes)) {
				s = switch (from) {
					case "mysql-slow" -> SlowLogConverter.convert(reader, writer, HeadPolicy::byDefault, buffer, masker, maskName, scope);
					case "postgres-csv" -> CsvLogConverter.convert(reader, writer, HeadPolicy::byDefault, buffer, masker, maskName, scope);
					default -> throw new IllegalArgumentException("모르는 로그 종류: " + from);
				};
				spills = buffer.spills();
			}
		}
		if (losses != null) {
			System.out.println("패킷 캡처: " + losses);
		}
		if (masker != null) {
			System.out.printf("리터럴을 가렸다 (수준: %s). 날짜와 시각 모양의 값, 주석, 식별자는 그대로다%n", mask);
		}
		if (scope.active()) {
			System.out.printf("걸러 냈다 (%s): 문장 %d개와 연결 종료 %d개를 뺐다%n", scope.describe(), scope.droppedStatements(),
					scope.droppedCloses());
			if (scope.unknownDatabase() > 0) {
				System.out.printf("  데이터베이스를 알 수 없어 남긴 문장 %d개. 로그에 데이터베이스가 찍히기 전의 문장이다%n", scope.unknownDatabase());
			}
		}
		if (spills > 0) {
			System.out.printf("메모리에 다 올리지 않고 임시 파일 %d개로 나눠 정렬했다%n", spills);
		}
		System.out.printf("로그 항목 %d개를 읽어 문장 %d개, 연결 종료 %d개를 썼다 (연결 %d개)%n",
				s.entries(), s.statements(), s.closes(), s.sessions());
		System.out.printf("뺀 것: 재생에 쓰지 않는 항목 %d, 머리에서 잘린 트랜잭션의 문장 %d%n", s.ignored(), s.droppedHead());
		if (s.skipped() > 0 || s.clamped() > 0) {
			// 시각을 맞춘 문장은 밀리초 단위로 찍는 PostgreSQL 로그에서는 흔하다. 한 연결 안의 순서는 그대로다
			System.out.printf("읽지 못한 항목 %d, 같은 연결의 앞 문장보다 이르게 계산돼 시각을 맞춘 문장 %d%n", s.skipped(), s.clamped());
		}
	}

	private static void replay(Map<String, String> options) throws Exception {
		JdbcTarget target = target(options);
		Replayer.Options replayOptions = replayOptions(options, number(options, "--speed", 1));
		String out = options.get("--out");

		String runOut = options.get("--run-out");
		boolean withMetrics = options.getOrDefault("--metrics", "off").equals("on") || options.containsKey("--metric-command")
				|| runOut != null;
		ReplaySummary s;
		NewErrors newErrors;
		ReplaySummary replicaSummary;
		Map<String, double[]> metrics = Map.of();
		List<String> failures = List.of();
		try (MetricSampler sampler = withMetrics ? sampler(target, options) : new MetricSampler();
				TraceReader trace = openTrace(required(options, "--trace"), shardOf(options));
				ResultWriter results = out == null ? null
						: new ResultWriter(Files.newBufferedWriter(Path.of(out), StandardCharsets.UTF_8))) {
			newErrors = new NewErrors(results == null ? result -> { } : results);
			// 장비 여러 대가 나눠 틀 때는 같은 시각에 시작하고, 전체 트래픽의 첫 문장을 같은 기준으로 삼는다(#81)
			waitUntil(options.get("--start-at"));
			ReplicaRun replicaRun = ReplicaRun.start(options, replayOptions.speed());
			s = options.containsKey("--shard")
					? Replayer.run(trace, target, newErrors, replayOptions, firstStartMicros(required(options, "--trace"), r -> true))
					: Replayer.run(trace, target, newErrors, replayOptions);
			replicaSummary = replicaRun == null ? null : replicaRun.join();
			metrics = sampler.stop();
			failures = sampler.failures();
		}
		print(s, replayOptions);
		printNewErrors(newErrors, required(options, "--trace"));
		if (replicaSummary != null) {
			System.out.printf("%n종 DB에 함께 튼 트래픽%n");
			print(replicaSummary, replicaOptions(options, replayOptions.speed()));
		}
		printMetrics(metrics);
		printFailures(failures);
		if (runOut != null) {
			JSON.writeValue(Path.of(runOut).toFile(), RunRecord.of(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
					unit(options).name().toLowerCase(Locale.ROOT), s, profile(target, options), metrics));
		}
	}

	private static void report(Map<String, String> options) throws IOException {
		TraceComparison comparison;
		try (TraceReader before = openTrace(required(options, "--before"));
				TraceReader after = openTrace(required(options, "--after"))) {
			comparison = TraceComparison.of(before, after);
		}
		MarkdownReport.Inputs inputs = new MarkdownReport.Inputs(options.getOrDefault("--title", "변경 전후 비교"), comparison,
				read(options, "--before-run", RunRecord.class), read(options, "--after-run", RunRecord.class),
				read(options, "--before-evaluation", Evaluation.class), read(options, "--after-evaluation", Evaluation.class),
				(int) number(options, "--top", 3), (long) number(options, "--min-count", 100));
		String out = required(options, "--out");
		try (Writer writer = Files.newBufferedWriter(Path.of(out), StandardCharsets.UTF_8)) {
			MarkdownReport.write(writer, inputs);
		}
		System.out.println("리포트를 " + out + "에 썼다");
	}

	private static <T> T read(Map<String, String> options, String name, Class<T> type) throws IOException {
		String path = options.get(name);
		return path == null ? null : JSON.readValue(Path.of(path).toFile(), type);
	}

	private static void sample(Map<String, String> options) throws Exception {
		JdbcTarget target = target(options);
		long seconds = (long) number(options, "--seconds", 30);
		Map<String, double[]> metrics;
		List<String> failures = List.of();
		try (MetricSampler sampler = sampler(target, options)) {
			Thread.sleep(seconds * 1_000);
			metrics = sampler.stop();
			failures = sampler.failures();
		}
		System.out.printf("%d초 동안 뜬 지표%n", seconds);
		printMetrics(metrics);
		printFailures(failures);
	}

	/** 종 DB에서 뜬 지표의 이름 앞에 붙인다. */
	static final String REPLICA_PREFIX = "replica.";

	private static MetricSampler sampler(JdbcTarget target, Map<String, String> options) throws Exception {
		MetricSampler sampler = new MetricSampler()
				.add(new DbMetricSource(target, (long) number(options, "--slow-ms", 100)), (long) number(options, "--sample-ms", 200));
		if (options.containsKey("--metric-command")) {
			sampler.add(new CommandMetricSource(options.get("--metric-command")), (long) number(options, "--metric-command-ms", 1_000));
		}
		if (options.containsKey("--server-log")) {
			// MySQL은 서버의 실행 시간 분포로 이미 센다. 같은 이름의 지표가 두 곳에서 오면 섞인다
			if (!target.isPostgres()) {
				throw new IllegalArgumentException("--server-log는 PostgreSQL의 csvlog에 쓴다. MySQL은 서버의 분포로 이미 센다");
			}
			sampler.add(new ServerLogSlowSource(Path.of(options.get("--server-log")), (long) number(options, "--slow-ms", 100)),
					(long) number(options, "--sample-ms", 200));
		}
		JdbcTarget replica = replica(options);
		if (replica != null) {
			sampler.add(new ReplicationLagSource(target, replica), (long) number(options, "--sample-ms", 200));
			// 종 DB에서도 같은 지표를 뜬다. 이름 앞의 replica.으로 가른다. 종 DB에 알람을 걸 수 있게 하려는 것이다(#80)
			sampler.add(new PrefixedMetricSource(REPLICA_PREFIX, new DbMetricSource(replica, (long) number(options, "--slow-ms", 100))),
					(long) number(options, "--sample-ms", 200));
		}
		return sampler;
	}

	/** 재생 요약의 오류 수는 숫자뿐이다. 어떤 문장이 무슨 오류로 실패했는지를 트래픽 파일에서 찾아 보여 준다. */
	private static void printNewErrors(NewErrors newErrors, String tracePath) throws IOException {
		if (newErrors.count() == 0) {
			return;
		}
		List<NewErrors.Row> rows;
		try (TraceReader trace = openTrace(tracePath)) {
			rows = newErrors.rows(trace);
		}
		System.out.printf("%n원본에서는 실패하지 않았는데 재생에서 실패한 문장 %d개%n", newErrors.count());
		System.out.printf("  %8s %8s  %s%n", "오류", "횟수", "모양");
		rows.stream().limit(10).forEach(r -> System.out.printf("  %8d %8d  %s%n", r.error(), r.count(), shorten(r.shape())));
		if (rows.size() > 10) {
			System.out.printf("  그 밖의 모양 %d개%n", rows.size() - 10);
		}
		if (newErrors.beyondLimit() > 0) {
			System.out.printf("  실패 %d개까지만 문장을 찾았다. 나머지 %d개는 개수만 셌다%n", NewErrors.LIMIT, newErrors.beyondLimit());
		}
	}

	private static void printMetrics(Map<String, double[]> metrics) {
		if (metrics.isEmpty()) {
			return;
		}
		System.out.printf("%n%-20s %8s %10s %10s %10s%n", "지표", "표본 수", "최소", "평균", "최대");
		metrics.forEach((name, values) -> {
			var stats = java.util.Arrays.stream(values).summaryStatistics();
			System.out.printf("%-20s %8d %10.2f %10.2f %10.2f%n", name, values.length, stats.getMin(), stats.getAverage(), stats.getMax());
		});
	}

	private static void compare(Map<String, String> options) throws IOException {
		int top = (int) number(options, "--top", 3);
		long minCount = (long) number(options, "--min-count", 100);
		TraceComparison c;
		try (TraceReader before = openTrace(required(options, "--before"));
				TraceReader after = openTrace(required(options, "--after"))) {
			c = TraceComparison.of(before, after);
		}
		TraceComparison.Side b = c.totalBefore();
		TraceComparison.Side a = c.totalAfter();
		System.out.printf("전체: 문장 %d → %d개, 실패 %d → %d개%n", b.count(), a.count(), b.errors(), a.errors());
		System.out.printf("      실행 시간 평균 %s → %s, 중앙값 %s → %s, p99 %s → %s%n",
				ms(b.meanMicros()), ms(a.meanMicros()), ms(b.quantile(0.5)), ms(a.quantile(0.5)),
				ms(b.quantile(0.99)), ms(a.quantile(0.99)));

		printRows("나빠진 SQL", c.worst(top, minCount));
		printRows("좋아진 SQL", c.best(top, minCount));
		printRows("실행 시간을 많이 쓴 순서", c.rows(minCount).stream().limit((long) number(options, "--rows", 15)).toList());

		// 읽은 행 수: 실행 시간이 비슷해도 실행 계획이 바뀌면 여기서 드러난다
		System.out.printf("%n읽은 행 수가 2배 이상 늘어난 SQL%n");
		if (!b.knowsRows() || !a.knowsRows()) {
			System.out.println("  이 녹화에는 읽은 행 수가 없다 (PostgreSQL의 로그에는 없다)");
		}
		else {
			List<TraceComparison.Row> more = c.moreRowsExamined(top, minCount, 2);
			if (more.isEmpty()) {
				System.out.println("  없음");
			}
			else {
				System.out.printf("  %8s %12s %12s %8s  %s%n", "횟수", "행 수 앞", "행 수 뒤", "배율", "모양");
				for (TraceComparison.Row r : more) {
					System.out.printf("  %8d %12.1f %12.1f %7.1f배  %s%n", r.after().count(), r.before().meanRowsExamined(),
							r.after().meanRowsExamined(), r.rowsRatio(), shorten(r.shape()));
				}
			}
		}

		System.out.printf("%n오류가 달라진 SQL%n");
		List<TraceComparison.Row> errorChanges = c.errorChanges();
		if (errorChanges.isEmpty()) {
			System.out.println("  없음");
		}
		for (TraceComparison.Row r : errorChanges) {
			System.out.printf("  새로 난 오류 %s, 사라진 오류 %s (실패 %d → %d)  %s%n", r.newErrors(), r.goneErrors(),
					r.before().errors(), r.after().errors(), shorten(r.shape()));
		}
		if (!c.onlyBefore().isEmpty() || !c.onlyAfter().isEmpty()) {
			// 실패한 문장을 로그에 적지 않는 서버가 있다(MySQL은 구문 오류로 끝난 문장을 슬로우 로그에 적지 않는다).
			// 그런 문장은 위의 '오류가 달라진 SQL'에 나오지 않고 여기에 '앞에만 있는 모양'으로 나온다
			System.out.printf("%n한쪽에만 있는 모양: 앞 %d개, 뒤 %d개%n", c.onlyBefore().size(), c.onlyAfter().size());
			printOneSided("앞에만", c.onlyBefore(), top);
			printOneSided("뒤에만", c.onlyAfter(), top);
		}
	}

	private static void printOneSided(String label, Map<String, TraceComparison.Side> shapes, int top) {
		shapes.entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue().count(), a.getValue().count()))
				.limit(top)
				.forEach(e -> System.out.printf("  %s %8d번 (실패 %d)  %s%n", label, e.getValue().count(), e.getValue().errors(),
						shorten(e.getKey())));
	}

	private static void printRows(String title, List<TraceComparison.Row> rows) {
		System.out.printf("%n%s%n", title);
		if (rows.isEmpty()) {
			System.out.println("  없음");
			return;
		}
		System.out.printf("  %8s %10s %10s %8s %10s %10s  %s%n", "횟수", "평균 앞", "평균 뒤", "변화", "p99 앞", "p99 뒤", "모양");
		for (TraceComparison.Row r : rows) {
			System.out.printf("  %8d %10s %10s %+7.1f%% %10s %10s  %s%n", r.after().count(),
					ms(r.before().meanMicros()), ms(r.after().meanMicros()), (r.ratio() - 1) * 100,
					ms(r.before().quantile(0.99)), ms(r.after().quantile(0.99)), shorten(r.shape()));
		}
	}

	private static String shorten(String shape) {
		return shape.length() > 90 ? shape.substring(0, 87) + "..." : shape;
	}

	private static void explore(Map<String, String> options) throws Exception {
		// 탐색은 몇 분이 걸린다. 잘못 준 값은 시작하기 전에 거른다
		checkScaleOptions(options);
		int repeat = repeat(options);
		JdbcTarget target = target(options);
		String tracePath = required(options, "--trace");
		Sandbox sandbox = options.containsKey("--snapshot") ? sandbox(target, options) : null;
		String resetCommand = sandbox == null ? required(options, "--reset-command") : null;
		Map<String, String> alarmSettings = alarmSettings(options);
		RoundJudge judge = judge(options);
		Explorer.Options defaults = Explorer.Options.defaults();
		Explorer.Options exploreOptions = new Explorer.Options(
				number(options, "--start-speed", defaults.startSpeed()),
				number(options, "--max-speed", defaults.maxSpeed()),
				number(options, "--precision", defaults.precision()),
				defaults.maxRounds());

		TrafficModel traffic;
		try (TraceReader trace = openTrace(tracePath)) {
			traffic = TrafficModel.of(trace);
		}
		// 쓰기만 틀면 쓰기가 든 트랜잭션만 고른다. 트랜잭션은 끝까지 봐야 알 수 있어 미리 한 번 읽는다(#81)
		Predicate<TraceRecord> selection = r -> true;
		if (writesOnly(options)) {
			try (TraceReader trace = openTrace(tracePath)) {
				selection = TraceSelection.writeUnits(trace);
			}
		}

		// 상한을 하나로 낼지, 되풀이해 범위로 낼지. 되풀이하지 않았으면 출력이 지금과 글자 하나 다르지 않다
		CeilingRange range = null;
		// 탐색마다의 라운드를 평가 파일에 남긴다. 되풀이하지 않았으면 하나짜리 목록이다
		List<Explorer.Result> results = new ArrayList<>();
		double ceilingSpeed;
		RoundJudge.State stoppedBy;
		if (repeat == 1) {
			Explorer.Result result = exploreOnce(target, sandbox, resetCommand, tracePath, selection, options, judge, exploreOptions);
			results.add(result);
			System.out.println();
			if (result.ceilingSpeed() == 0) {
				System.out.printf("상한: %.2f배속에서도 버티지 못했다. 녹화한 부하보다 낮다%n", exploreOptions.startSpeed());
				return;
			}
			System.out.printf("상한: %.2f배속%s%n", result.ceilingSpeed(), stoppedBySuffix(result.stoppedBy()));
			singleRunNote(repeat).forEach(System.out::println);
			ceilingSpeed = result.ceilingSpeed();
			stoppedBy = result.stoppedBy();
		}
		else {
			for (int i = 1; i <= repeat; i++) {
				System.out.printf("=== 탐색 %d/%d ===%n", i, repeat);
				Explorer.Result one = exploreOnce(target, sandbox, resetCommand, tracePath, selection, options, judge, exploreOptions);
				results.add(one);
				System.out.printf("%n탐색 %d/%d의 상한: %s%n%n", i, repeat,
						ceilingText(one.ceilingSpeed(), one.stoppedBy(), exploreOptions.startSpeed()));
			}
			range = CeilingRange.of(results);
			rangeLines(range).forEach(System.out::println);
			// 시작 배속에서도 못 버틴 탐색이 있으면 상한이 0이다. 지금 상한이 0일 때와 같이 수위 없이 끝낸다
			if (range.notHeld() > 0) {
				System.out.printf("  %d번은 시작 배속(%.2f)에서도 버티지 못했다. 녹화한 부하보다 낮다%n", range.notHeld(),
						exploreOptions.startSpeed());
				return;
			}
			ceilingSpeed = range.representative();
			stoppedBy = range.stoppedByAtMin();
		}

		// 읽기만 튼 탐색이면 상한과 수위도 읽기의 것이다
		double recordedQps = readsOnly(options) ? traffic.readQps() : writesOnly(options) ? traffic.writeQps() : traffic.qps();
		double ceilingQps = recordedQps * ceilingSpeed;
		double currentQps = number(options,
				readsOnly(options) ? "--current-read-qps" : writesOnly(options) ? "--current-write-qps" : "--current-qps", recordedQps);
		WaterLevel level = new WaterLevel(currentQps, ceilingQps,
				number(options, "--level-low", 0.30), number(options, "--level-high", 0.50));
		printLevel(level, recordedQps);
		if (range != null) {
			// 최솟값으로 판정한 수위와 최댓값으로 본 수위가 갈리는지 알려 준다. 갈리면 판정을 하나로 말할 수 없다
			WaterLevel atMax = range.levelAtMax(recordedQps, currentQps, level.low(), level.high());
			if (range.verdictSplits(recordedQps, currentQps, level.low(), level.high())) {
				System.out.printf("  가장 높은 상한(%.2f배속)으로 보면 수위 %.0f%%, %s이다. 판정이 갈린다%n",
						range.max(), atMax.level() * 100, verdictName(atMax.verdict()));
			}
			else {
				System.out.printf("  가장 높은 상한(%.2f배속)으로 봐도 판정은 같다 (수위 %.0f%%)%n", range.max(),
						atMax.level() * 100);
			}
		}
		// 종 DB를 함께 틀었으면 그 수위도 따로 낸다. 종 DB의 지금 QPS는 --current-replica-qps, 없으면 녹화 QPS를 쓴다
		Evaluation.Replica replica = null;
		WaterLevel replicaLevel = null;
		boolean replicaConfirmed = false;
		if (options.containsKey("--replica-trace")) {
			TrafficModel replicaTraffic;
			try (TraceReader trace = openTrace(options.get("--replica-trace"))) {
				replicaTraffic = TrafficModel.of(trace);
			}
			// 위의 수위는 주 DB의 트래픽만 센 것이다. 종 DB의 것은 같은 배속을 곱해 따로 보여 준다
			double replicaCeilingQps = replicaTraffic.qps() * ceilingSpeed;
			double currentReplicaQps = number(options, "--current-replica-qps", replicaTraffic.qps());
			System.out.printf("종 DB에 함께 튼 트래픽: 녹화 구간의 초당 문장 수 %.0f, 상한 배속에서 %.0f%n",
					replicaTraffic.qps(), replicaCeilingQps);
			System.out.println(replicaLevelLine(currentReplicaQps, replicaCeilingQps, level.low(), level.high()));
			replicaConfirmed = replicaCeilingConfirmed(results);
			System.out.println(replicaConfirmed ? "  종 DB의 상한은 종 DB의 알람으로 확인됐다"
					: "  종 DB의 상한은 주 DB가 버틴 배속을 곱한 어림값이다. 탐색을 멈춘 알람이 종 DB의 것이 아니다");
			replicaLevel = new WaterLevel(currentReplicaQps, replicaCeilingQps, level.low(), level.high());
			replica = new Evaluation.Replica(replicaTraffic, replicaCeilingQps, currentReplicaQps, replicaConfirmed);
		}
		ScaleAdvice advice = printReadWrite(options, traffic, ceilingSpeed, level, replicaLevel);

		String out = options.get("--out");
		if (out != null) {
			// 탐색마다의 라운드를 리포트의 상승 과정에 쓰려고 남긴다
			List<List<Evaluation.RoundLog>> explorations = results.stream()
					.map(exploration -> exploration.rounds().stream().map(Evaluation.RoundLog::of).toList())
					.toList();
			Evaluation evaluation = new Evaluation(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
					(int) number(options, "--valid-days", 30), profile(target, options), alarmSettings,
					unit(options).name().toLowerCase(Locale.ROOT), traffic, ceilingSpeed,
					stoppedBy == null ? null : stoppedBy.name(), ceilingQps, currentQps,
					level.low(), level.high(), range == null ? null : range.ceilings(), explorations, replica);
			JSON.writeValue(Path.of(out).toFile(), evaluation);
			System.out.printf("평가를 %s에 적었다. %d일 동안 유효하다%n", out, evaluation.validDays());
		}
		// 평가를 적은 뒤에 한다. 명령이 실패해도 잰 값은 남는다
		if (advice != null) {
			// 되풀이했으면 N번 가운데 하나라도 알람으로 확인되지 않았으면 실행하지 않는다
			boolean confirmed = range == null ? stoppedBy == RoundJudge.State.OVERLOADED : range.allConfirmed();
			boolean adviceFromReplica = replicaLevel != null;
			// 종 DB의 상한은 주 DB와 같은 배속 × 종 DB의 녹화 QPS다. 탐색을 멈춘 알람이 주 DB의 것이면 종 DB의 한계는
			// 알람으로 확인된 적이 없는 어림값이다. 왜 실행하지 않는지 먼저 밝힌다
			if (adviceFromReplica && !replicaConfirmed && options.containsKey("--scale-command")) {
				System.out.println("  종 DB의 상한은 주 DB와 같은 배속을 곱해 어림한 값이라 확장 명령을 실행하지 않는다");
			}
			applyScale(options, advice, scaleConfirmed(confirmed, adviceFromReplica, replicaConfirmed));
		}
	}

	/**
	 * 확장 명령을 실행해도 되는가. 종 DB의 수위로 계산한 권고는, 탐색을 멈춘 알람이 종 DB의 지표에 건 것일 때만
	 * 종 DB의 상한이 알람으로 확인된 것이다. 아니면 주 DB가 버틴 배속을 곱한 어림값이다(#53, #80).
	 */
	static boolean scaleConfirmed(boolean ceilingConfirmed, boolean adviceFromReplica, boolean replicaConfirmed) {
		return ceilingConfirmed && (!adviceFromReplica || replicaConfirmed);
	}

	/**
	 * 종 DB의 상한이 종 DB의 알람으로 확인됐는가. 탐색마다 과부하로 판정된 가장 낮은 배속의 라운드를 멈춘 알람이
	 * replica.으로 시작하는 지표에 건 것이어야 한다. 되풀이했으면 모든 탐색에서 그래야 한다.
	 */
	static boolean replicaCeilingConfirmed(List<Explorer.Result> results) {
		if (results.isEmpty()) {
			return false;
		}
		for (Explorer.Result result : results) {
			Explorer.Round boundary = result.rounds().stream()
					.filter(r -> r.verdict().state() == RoundJudge.State.OVERLOADED)
					.min(java.util.Comparator.comparingDouble(Explorer.Round::speed))
					.orElse(null);
			if (boundary == null || boundary.verdict().reason() == null || !boundary.verdict().reason().startsWith(REPLICA_PREFIX)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 탐색 한 번을 돌린다. 라운드 표의 머리말, 탐색, 라운드별 지표 표까지를 낸다. 되풀이할 때 이 하나를 N번 부른다.
	 * 탐색 한 번의 알고리즘과 라운드의 내용은 되풀이를 넣기 전과 같다.
	 */
	private static Explorer.Result exploreOnce(JdbcTarget target, Sandbox sandbox, String resetCommand, String tracePath,
			Predicate<TraceRecord> selection, Map<String, String> options, RoundJudge judge, Explorer.Options exploreOptions)
			throws Exception {
		int workers = workers(options);
		System.out.printf("%-6s %7s %9s %9s %10s %9s %9s  %s%n",
				"라운드", "배속", "계획(초)", "실제(초)", "늦음 p99", "왜곡", "실행 중", "판정");
		Explorer.Result result = Explorer.explore(speed -> {
			if (sandbox != null) {
				sandbox.reset();
			}
			else {
				run(resetCommand);
			}
			long started = System.currentTimeMillis();
			ReplaySummary s;
			ReplaySummary replicaSummary;
			Map<String, double[]> metrics;
			try (MetricSampler sampler = sampler(target, options)) {
				ReplicaRun replicaRun = ReplicaRun.start(options, speed);
				s = replayInShards(tracePath, selection, target, replayOptions(options, speed), workers);
				replicaSummary = replicaRun == null ? null : replicaRun.join();
				metrics = sampler.stop();
				// 라운드 도중에 지표를 읽다 실패했으면 여기서 멈춘다. 그 뒤의 값이 없어 알람이 못 본 것을 버틴 것으로 읽게 된다
				requireNoFailures(sampler.failures());
			}
			return new RoundData(speed, started, System.currentTimeMillis(), s, replicaSummary, metrics);
		}, judge, exploreOptions, round -> {
			ReplaySummary s = round.data().summary();
			System.out.printf("%-6d %7.2f %9.2f %9.2f %10s %8.1f%% %9d  %s%n", round.number(), round.speed(),
					s.traceSpanMicros() / round.speed() / 1e6, s.elapsedMicros() / 1e6, ms(s.lag().quantile(0.99)),
					round.data().distortion() * 100, round.data().maxActiveSessions(),
					switch (round.verdict().state()) {
						case HELD -> "버팀";
						case OVERLOADED -> "과부하: " + round.verdict().reason();
						case DISTORTED -> "왜곡: " + round.verdict().reason();
					});
		});

		System.out.printf("%n라운드별 지표 (평균 / 최대)%n");
		java.util.Set<String> names = new java.util.TreeSet<>();
		result.rounds().forEach(r -> names.addAll(r.data().metrics().keySet()));
		System.out.printf("%-6s %7s", "라운드", "배속");
		names.forEach(n -> System.out.printf(" %22s", n));
		System.out.println();
		for (Explorer.Round r : result.rounds()) {
			System.out.printf("%-6d %7.2f", r.number(), r.speed());
			for (String n : names) {
				System.out.printf(" %22s", String.format("%.1f / %.1f", r.data().mean(n), r.data().max(n)));
			}
			System.out.println();
		}
		return result;
	}

	/** 되풀이 횟수. --repeat을 주지 않았으면 1. 1 이상의 정수가 아니면 탐색을 시작하기 전에 거른다. */
	static int repeat(Map<String, String> options) {
		String value = options.get("--repeat");
		if (value == null) {
			return 1;
		}
		int count;
		try {
			count = Integer.parseInt(value);
		}
		catch (NumberFormatException e) {
			throw new IllegalArgumentException("--repeat에는 1 이상의 정수를 준다");
		}
		if (count < 1) {
			throw new IllegalArgumentException("--repeat에는 1 이상의 정수를 준다");
		}
		return count;
	}

	/** "상한:" 줄에서 배속 뒤에 붙는 글자. 상한을 만든 판정에 따라 다르다. */
	static String stoppedBySuffix(RoundJudge.State stoppedBy) {
		return stoppedBy == null ? " 이상 (이 배속까지 알람이 울리지 않았다)"
				: stoppedBy == RoundJudge.State.DISTORTED
						? " 이상 (그 위는 재생기가 내지 못해 대상의 한계를 보지 못했다)"
						: "";
	}

	/** 되풀이한 탐색 한 번의 "탐색 i/N의 상한:" 줄의 뒷부분. 시작 배속에서도 못 버틴 탐색은 다르게 적는다. */
	private static String ceilingText(double ceilingSpeed, RoundJudge.State stoppedBy, double startSpeed) {
		if (ceilingSpeed == 0) {
			return String.format("%.2f배속에서도 버티지 못했다", startSpeed);
		}
		return String.format("%.2f배속%s", ceilingSpeed, stoppedBySuffix(stoppedBy));
	}

	/**
	 * 한 번만 탐색했을 때 상한 밑에 붙이는 안내. 숫자 하나는 믿을 만해 보이지만 같은 절차의 상한이 크게 흔들렸다(실험 12).
	 * 기본 횟수 1은 범수 님이 유지하기로 했고, 대신 이 줄을 붙인다(#14, #76). 평가 파일에는 넣지 않는다.
	 */
	static List<String> singleRunNote(int repeat) {
		if (repeat != 1) {
			return List.of();
		}
		return List.of("  한 번 탐색한 값이다. 같은 절차를 되풀이하면 크게 달라질 수 있다(실험 12에서 최대와 최소의 차이가 중앙값의 36~97%).",
				"  --repeat N으로 범위를 본다");
	}

	/** 되풀이한 상한들의 요약 줄. 패키지 안에서 테스트할 수 있게 글자만 돌려준다. */
	static List<String> rangeLines(CeilingRange range) {
		String every = range.ceilings().stream().map(ceiling -> String.format("%.2f", ceiling))
				.collect(java.util.stream.Collectors.joining(", "));
		return List.of(
				String.format("상한: 최소 %.2f, 중앙값 %.2f, 최대 %.2f배속 (%d번 탐색, 퍼짐 %.0f%%)", range.min(),
						range.median(), range.max(), range.count(), range.spread() * 100),
				"  탐색마다: " + every,
				"  수위는 가장 낮은 상한으로 판정한다");
	}

	private static String verdictName(WaterLevel.Verdict verdict) {
		return switch (verdict) {
			case SURPLUS -> "과잉";
			case HEALTHY -> "적정";
			case SHORT -> "부족";
		};
	}

	/** check --current-qps: 평가 파일의 상한은 그대로 두고 지금의 초당 문장 수로 수위를 다시 계산한 줄. */
	static String currentQpsLine(double ceilingQps, double currentQps, double low, double high) {
		WaterLevel level = new WaterLevel(currentQps, ceilingQps, low, high);
		return String.format("  지금의 초당 %.0f문장으로 다시 계산한 수위: %.0f%% (건강 구간 %.0f~%.0f%%) → %s",
				currentQps, level.level() * 100, low * 100, high * 100, verdictName(level.verdict()));
	}

	/** 종 DB의 수위 줄. 주 DB의 수위(printLevel)는 여러 줄이지만 종 DB는 이 한 줄로만 낸다. */
	static String replicaLevelLine(double currentQps, double ceilingQps, double low, double high) {
		WaterLevel level = new WaterLevel(currentQps, ceilingQps, low, high);
		return String.format("종 DB의 수위: %.0f%% (초당 %.0f ÷ 상한 %.0f), 건강 구간 %.0f~%.0f%% → %s",
				level.level() * 100, currentQps, ceilingQps, low * 100, high * 100, verdictName(level.verdict()));
	}

	/**
	 * 라운드에서 지표를 읽다 실패했으면 탐색을 멈춘다. 그 뒤의 값이 없으면 알람이 못 본 것을 버틴 것으로 읽게 되어
	 * 상한이 실제보다 높게 나온다.
	 */
	static void requireNoFailures(List<String> failures) {
		if (!failures.isEmpty()) {
			throw new IllegalStateException("이 라운드에서 지표를 읽다 실패했다: " + String.join("; ", failures)
					+ ". 그 뒤의 값이 없어 알람이 못 본 것을 버틴 것으로 읽게 된다. 탐색을 멈춘다");
		}
	}

	/** 표본기가 지표를 읽다 멈춘 것이 있으면 알린다. 그 뒤의 값은 빠져 있다. */
	private static void printFailures(List<String> failures) {
		if (!failures.isEmpty()) {
			System.out.println("지표를 읽다 실패했다: " + String.join("; ", failures) + ". 그 뒤의 값은 빠져 있다");
		}
	}

	private static Sandbox sandbox(JdbcTarget target, Map<String, String> options) {
		return new Sandbox(target, replica(options), new Sandbox.Options(required(options, "--snapshot"), options.get("--snapshot-db"),
				options.get("--change-command"), options.get("--ready-command"), (long) number(options, "--settle-seconds", 60)));
	}

	private static void reset(Map<String, String> options) throws Exception {
		Sandbox.Timing t = sandbox(target(options), options).reset();
		System.out.printf("되돌리기 %.1f초: 덤프 되붓기 %.1f, 변경 %.1f, 통계 %.1f, 조용해질 때까지 %.1f (%s)%n",
				t.totalMillis() / 1e3, t.restoreMillis() / 1e3, t.changeMillis() / 1e3, t.analyzeMillis() / 1e3,
				t.settleMillis() / 1e3, t.settled() ? "조용해짐" : "기다리지 않았거나 시간 안에 조용해지지 않음");
		if (options.containsKey("--replica-url")) {
			System.out.printf("  그중 종 DB가 주 DB를 따라올 때까지 %.1f초%n", t.replicaMillis() / 1e3);
		}
	}

	/** 녹화를 시작하기 직전에 뜬 스냅샷을 reset과 explore의 --snapshot에 그대로 준다. */
	private static void snapshot(Map<String, String> options) throws Exception {
		Path out = Path.of(required(options, "--out"));
		Snapshot snapshot = new Snapshot(target(options), options.get("--snapshot-db"));
		long started = System.currentTimeMillis();
		long bytes = snapshot.dump(out);
		System.out.printf("스냅샷을 %s에 떴다 (%.1fMB, %.1f초)%n", out, bytes / 1e6,
				(System.currentTimeMillis() - started) / 1e3);
		// 새 pg_dump가 서버보다 새 버전이면 모르는 설정 줄을 덤프에서 뺐다. 무엇을 뺐는지 알려 준다
		if (!snapshot.droppedSettings().isEmpty()) {
			List<String> names = snapshot.droppedSettings().stream().map(Snapshot::settingName).toList();
			System.out.printf("  서버가 모르는 설정 %d줄을 덤프에서 뺐다 (pg_dump가 서버보다 새 버전이다): %s%n",
					snapshot.droppedSettings().size(), String.join(", ", names));
		}
	}

	private static void printLevel(WaterLevel level, double recordedQps) {
		System.out.printf("녹화 구간의 초당 문장 수 %.0f, 상한 %.0f%n", recordedQps, level.ceilingQps());
		System.out.printf("수위: %.0f%% (초당 %.0f ÷ 상한 %.0f), 건강 구간 %.0f~%.0f%% → %s%n",
				level.level() * 100, level.currentQps(), level.ceilingQps(), level.low() * 100, level.high() * 100,
				switch (level.verdict()) {
					case SURPLUS -> "과잉";
					case HEALTHY -> "적정";
					case SHORT -> "부족";
				});
		switch (level.verdict()) {
			case SHORT -> System.out.printf("수위를 %.0f%%로 내리려면 용량이 지금의 %.2f배 필요하다%n",
					level.high() * 100, level.capacityFactor());
			case SURPLUS -> System.out.printf("용량을 지금의 %.2f배까지 줄여도 수위가 %.0f%%를 넘지 않는다%n",
					level.capacityFactor(), level.high() * 100);
			case HEALTHY -> { }
		}
	}

	/**
	 * 읽기와 쓰기의 상한과 수위를 따로 낸다. 메이투안 글은 읽기 QPS 상한과 쓰기 QPS 상한을 따로 보여 준다.
	 * 상한 배속 하나를 각각의 QPS에 곱한 값이다. 읽기와 쓰기가 지금의 비율로 함께 늘 때의 상한이라는 뜻이다.
	 */
	/** @return 확장·축소 권고. --read-nodes를 주지 않았거나 읽기와 쓰기를 나눠 볼 수 없으면 null */
	private static ScaleAdvice printReadWrite(Map<String, String> options, TrafficModel traffic, double ceilingSpeed,
			WaterLevel level, WaterLevel replicaLevel) {
		if (traffic.reads() + traffic.writes() == 0) {
			return null;
		}
		if (readsOnly(options)) {
			System.out.println("읽기만 튼 탐색이다. 위의 초당 문장 수와 상한, 수위는 읽기 문장만 센 것이다");
			return null;
		}
		if (writesOnly(options)) {
			System.out.println("쓰기만 튼 탐색이다. 쓰기가 든 트랜잭션만 틀었고, 위의 초당 문장 수와 상한, 수위는 쓰기 문장만 센 것이다");
			return null;
		}
		System.out.printf("%n읽기와 쓰기가 지금의 비율(읽기 %.0f%%)로 함께 늘 때:%n",
				100.0 * traffic.reads() / (traffic.reads() + traffic.writes()));
		WaterLevel[] levels = new WaterLevel[2];
		for (String kind : List.of("읽기", "쓰기")) {
			boolean read = kind.equals("읽기");
			double recorded = read ? traffic.readQps() : traffic.writeQps();
			double current = number(options, read ? "--current-read-qps" : "--current-write-qps", recorded);
			WaterLevel l = new WaterLevel(current, recorded * ceilingSpeed, level.low(), level.high());
			levels[read ? 0 : 1] = l;
			System.out.printf("  %s: 초당 %.0f, 상한 %.0f, 수위 %.0f%%%n", kind, current, l.ceilingQps(), l.level() * 100);
		}
		System.out.println("  읽기만 늘 때의 상한은 --only reads로 따로 탐색한다");
		if (!options.containsKey("--read-nodes") && !options.containsKey("--zones")) {
			return null;
		}
		// 종 DB를 함께 틀었으면 읽기 노드는 종 DB다. 읽기 노드 권고를 종 DB의 수위로 계산한다
		WaterLevel readLevel = readLevelForAdvice(replicaLevel, levels[0]);
		ScaleAdvice advice = ScaleAdvice.of(readLevel, levels[1], readNodes(options), minReadNodes(options),
				nodes(options, "--max-read-nodes", Integer.MAX_VALUE));
		if (replicaLevel != null) {
			System.out.println("  읽기 노드의 권고는 종 DB의 수위로 계산했다");
		}
		printScaleAdvice(advice);
		if (options.containsKey("--zones")) {
			zoneLines(zonePlan(options, advice, readLevel.level()), readLevel.high()).forEach(System.out::println);
		}
		if (options.containsKey("--max-write-scale")) {
			double limit = number(options, "--max-write-scale", 1);
			int shards = advice.shards(limit);
			if (shards > 1) {
				System.out.printf("  분할: 쓰기에 주 DB의 용량이 지금의 %.2f배 필요한데 키울 수 있는 한도는 %.2f배다. %d조각으로 나누는 것을 권한다%n",
						advice.writeCapacityFactor(), limit, shards);
			}
		}
		return advice;
	}

	/** 읽기를 받는 노드 수. --zones를 주면 존들의 합이고, --read-nodes도 주면 둘이 같아야 한다. */
	static int readNodes(Map<String, String> options) {
		if (!options.containsKey("--zones")) {
			return nodes(options, "--read-nodes", 1);
		}
		int sum = ZonePlan.parse(options.get("--zones")).values().stream().mapToInt(Integer::intValue).sum();
		if (options.containsKey("--read-nodes") && nodes(options, "--read-nodes", sum) != sum) {
			throw new IllegalArgumentException("--read-nodes와 --zones의 합이 다르다");
		}
		return sum;
	}

	/** 지켜야 하는 최소 노드 수. 존마다의 최소를 주면 존의 수를 곱한 것보다 적게 권하지 않는다. */
	static int minReadNodes(Map<String, String> options) {
		int min = nodes(options, "--min-read-nodes", 1);
		if (options.containsKey("--zones")) {
			min = Math.max(min, nodes(options, "--min-per-zone", 1) * ZonePlan.parse(options.get("--zones")).size());
		}
		return min;
	}

	private static ZonePlan zonePlan(Map<String, String> options, ScaleAdvice advice, double readLevel) {
		return ZonePlan.of(ZonePlan.parse(options.get("--zones")), advice.recommendedReadNodes(),
				nodes(options, "--min-per-zone", 1), readLevel);
	}

	/** 존마다의 권고와 재해 복구 점검. 패키지 안에서 테스트할 수 있게 글자만 돌려준다. */
	static List<String> zoneLines(ZonePlan plan, double high) {
		StringBuilder zones = new StringBuilder();
		plan.after().forEach((zone, n) -> zones.append(zones.isEmpty() ? "" : ", ").append(zone).append(' ')
				.append(plan.before().get(zone)).append(" → ").append(n));
		List<String> lines = new ArrayList<>();
		lines.add("  존별: " + zones + " (가장 적은 존부터 채우고 가장 많은 존부터 뺀다)");
		if (Double.isInfinite(plan.levelIfLost())) {
			lines.add(String.format("  존 %s 하나가 빠지면 읽기 노드가 남지 않는다", plan.lostZone()));
		}
		else {
			lines.add(String.format("  가장 많은 존(%s) 하나가 빠지면 남은 노드 %d개의 읽기 수위가 %.0f%%다. 건강 구간의 위 끝(%.0f%%)을 %s",
					plan.lostZone(), plan.total() - plan.after().get(plan.lostZone()), plan.levelIfLost() * 100, high * 100,
					plan.levelIfLost() > high ? "넘는다" : "넘지 않는다"));
		}
		return lines;
	}

	/** 읽기 노드 권고에 쓸 수위. 종 DB를 함께 틀었으면 종 DB의 수위, 아니면 주 DB 트래픽의 읽기 수위. */
	static WaterLevel readLevelForAdvice(WaterLevel replicaLevel, WaterLevel primaryReadLevel) {
		return replicaLevel != null ? replicaLevel : primaryReadLevel;
	}

	private static void checkScaleOptions(Map<String, String> options) {
		ScaleAdvice.checkNodes(readNodes(options), minReadNodes(options), nodes(options, "--max-read-nodes", Integer.MAX_VALUE));
		checkQpsOptions(options);
	}

	/** 지금의 초당 문장 수 옵션들은 0 이상의 유한한 수여야 한다. 음수·NaN·무한대는 탐색이나 확인을 시작하기 전에 거른다. */
	static void checkQpsOptions(Map<String, String> options) {
		for (String name : List.of("--current-qps", "--current-read-qps", "--current-write-qps", "--current-replica-qps")) {
			double qps = number(options, name, 0);
			if (!(qps >= 0) || Double.isInfinite(qps)) {
				throw new IllegalArgumentException(name + "에는 0 이상의 수를 준다");
			}
		}
	}

	private static int nodes(Map<String, String> options, String name, int fallback) {
		String value = options.get(name);
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(value);
		}
		catch (NumberFormatException e) {
			throw new IllegalArgumentException(name + "에는 정수를 준다");
		}
	}

	private static void printScaleAdvice(ScaleAdvice advice) {
		System.out.printf("%n확장·축소 권고 (읽기 상한이 노드 수에 비례한다는 가정이다. 확인된 것이 아니다)%n");
		if (advice.readNodeChange() == 0) {
			System.out.printf("  읽기: 노드 %d개를 그대로 둔다%n", advice.readNodes());
		}
		else {
			System.out.printf("  읽기: 노드 %d개 → %d개 (%s)%n", advice.readNodes(), advice.recommendedReadNodes(),
					advice.readNodeChange() > 0 ? advice.readNodeChange() + "개 늘린다" : -advice.readNodeChange() + "개 줄일 수 있다");
		}
		switch (advice.writeVerdict()) {
			case SHORT -> System.out.printf("  쓰기: 주 DB의 용량이 지금의 %.2f배 필요하다. 노드를 늘려서는 풀리지 않는다%n", advice.writeCapacityFactor());
			case SURPLUS -> System.out.printf("  쓰기: 주 DB의 용량을 지금의 %.2f배까지 줄여도 된다%n", advice.writeCapacityFactor());
			case HEALTHY -> System.out.println("  쓰기: 그대로 둔다");
		}
		if (advice.capped()) {
			System.out.println("  계산한 노드 수가 한도를 넘어 한도로 잘랐다. 상한이나 지금의 QPS를 다시 확인한다");
		}
	}

	/** 확장·축소 명령이 실패했다. 실행됐다고 믿고 다음 일을 하면 안 되므로 예외로 올린다. */
	private static final class ScaleCommandFailed extends IOException {
		ScaleCommandFailed(String message) {
			super(message);
		}
	}

	/** 권고대로 확장·축소 명령을 실행한다. 의심스러운 권고는 실행하지 않고, 실패는 삼키지 않는다. */
	private static void applyScale(Map<String, String> options, ScaleAdvice advice, boolean ceilingConfirmed)
			throws IOException, InterruptedException {
		String command = options.get("--scale-command");
		if (command == null || advice.readNodeChange() == 0) {
			return;
		}
		String refused = advice.whyNotApplied(options.containsKey("--max-read-nodes"), ceilingConfirmed);
		if (refused == null && !"yes".equals(options.get("--apply-scale"))) {
			refused = "--apply-scale yes를 주면 실행한다";
		}
		if (refused != null) {
			System.out.println("확장·축소 명령을 실행하지 않았다. " + refused);
			return;
		}
		Map<String, String> after = new HashMap<>();
		after.put("DBREPLAY_READ_NODES", Integer.toString(advice.recommendedReadNodes()));
		after.put("DBREPLAY_READ_NODES_BEFORE", Integer.toString(advice.readNodes()));
		Map<String, String> before = new HashMap<>();
		before.put("DBREPLAY_READ_NODES", Integer.toString(advice.readNodes()));
		if (options.containsKey("--zones")) {
			after.put("DBREPLAY_READ_NODES_BY_ZONE", zonePlan(options, advice, 0).afterText());
			before.put("DBREPLAY_READ_NODES_BY_ZONE", options.get("--zones").replace(" ", ""));
		}
		ScaleRun.Outcome outcome = ScaleRun.execute(command, options.get("--scale-verify-command"),
				options.get("--scale-rollback-command"), after, before, advice.readNodes(), advice.recommendedReadNodes(),
				Main::runCommand, Main::fingerprint, java.time.Clock.systemUTC());
		if (options.containsKey("--scale-log")) {
			appendScaleLog(Path.of(options.get("--scale-log")), outcome.entries());
		}
		String failure = scaleFailure(outcome);
		if (failure != null) {
			throw new ScaleCommandFailed(failure);
		}
		// 도구는 실제 노드 수를 모른다. 받은 값이 낡으면 다음 권고가 틀어진다
		System.out.printf("확장·축소 명령을 실행했다%s. 다음부터는 --read-nodes %d로 준다%n",
				options.containsKey("--scale-verify-command") ? "고 확인 명령이 성공했다" : "", advice.recommendedReadNodes());
	}

	private static int runCommand(String command, Map<String, String> env) throws IOException, InterruptedException {
		ProcessBuilder builder = new ProcessBuilder("sh", "-c", command).inheritIO();
		builder.environment().putAll(env);
		return builder.start().waitFor();
	}

	/** 실패를 알리는 문장. 성공했으면 null. 되돌렸는지와 그 결과를 함께 적는다. 명령의 원문은 싣지 않는다. */
	static String scaleFailure(ScaleRun.Outcome outcome) {
		if (outcome.succeeded()) {
			return null;
		}
		ScaleRun.Entry failed = outcome.entries().stream().filter(e -> e.step().equals(outcome.failedStep())).findFirst().orElseThrow();
		String what = failed.step().equals("scale") ? "확장·축소 명령" : "확인 명령";
		String message = failed.exit() < 0 ? what + "을 실행하지 못했다" : what + "이 실패했다 (종료 코드 " + failed.exit() + ")";
		if (!outcome.rolledBack()) {
			return message + ". 되돌리기 명령이 없어 그대로 두었다";
		}
		return message + (outcome.rollbackSucceeded() ? ". 되돌리기 명령을 실행했다" : ". 되돌리기 명령도 실패했다. 노드 수를 직접 확인한다");
	}

	/** 실행 기록을 JSON Lines로 덧붙인다. 여러 번의 실행이 한 파일에 쌓인다. */
	private static void appendScaleLog(Path path, List<ScaleRun.Entry> entries) throws IOException {
		ObjectMapper plain = new ObjectMapper();
		StringBuilder lines = new StringBuilder();
		for (ScaleRun.Entry e : entries) {
			lines.append(plain.writeValueAsString(e)).append('\n');
		}
		Files.writeString(path, lines, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.APPEND);
	}

	private static boolean readsOnly(Map<String, String> options) {
		return "reads".equals(only(options));
	}

	/** --shard i/N을 줬으면 그 조각만, 아니면 전부. */
	private static Predicate<TraceRecord> shardOf(Map<String, String> options) {
		return options.containsKey("--shard") ? TraceSelection.shard(options.get("--shard")) : r -> true;
	}

	/** --start-at(에포크 밀리초)까지 기다린다. 없으면 바로 간다. */
	private static void waitUntil(String epochMillis) throws InterruptedException {
		if (epochMillis == null) {
			return;
		}
		long wait = Long.parseLong(epochMillis) - System.currentTimeMillis();
		if (wait > 0) {
			Thread.sleep(wait);
		}
	}

	/** 쓰기가 든 트랜잭션만 튼다. 쓰기의 상한을 따로 볼 때 쓴다(#81). 트랜잭션 단위로 내보낸다. */
	private static boolean writesOnly(Map<String, String> options) {
		return "writes".equals(only(options));
	}

	private static String only(Map<String, String> options) {
		String only = options.get("--only");
		if (only != null && !only.equals("reads") && !only.equals("writes")) {
			throw new IllegalArgumentException("--only에는 reads나 writes를 준다");
		}
		return only;
	}

	/** 라운드마다 나란히 돌릴 재생기 수. 기본 1. */
	static int workers(Map<String, String> options) {
		int workers = nodes(options, "--workers", 1);
		if (workers < 1) {
			throw new IllegalArgumentException("--workers에는 1 이상의 정수를 준다");
		}
		return workers;
	}

	/**
	 * 트래픽을 연결 번호로 나눠 재생기 여러 개가 함께 튼다(#81). 글은 재생 작업마다 Kubernetes Job을 띄워 재생기를 늘린다.
	 * 이 도구는 한 프로세스 안에서 재생기를 여러 개 띄운다. 재생기마다 타이머, 넘겨주는 스레드, 연결 풀이 따로라 한 재생기가
	 * 먼저 막히는 일(실험 07)을 나눈다. 조각들은 전체의 첫 문장 시각을 같은 기준으로 삼는다. 요약은 하나로 모은다.
	 * 장비를 넘어 나누려면 장비마다 replay --shard를 같은 --start-at으로 돌린다.
	 */
	private static ReplaySummary replayInShards(String tracePath, Predicate<TraceRecord> selection, JdbcTarget target,
			Replayer.Options replayOptions, int workers) throws Exception {
		if (workers == 1) {
			try (TraceReader trace = openTrace(tracePath, selection)) {
				return Replayer.run(trace, target, r -> { }, replayOptions);
			}
		}
		long base = firstStartMicros(tracePath, selection);
		List<CompletableFuture<ReplaySummary>> parts = new ArrayList<>();
		for (int i = 0; i < workers; i++) {
			Predicate<TraceRecord> shard = selection.and(TraceSelection.shard(i, workers));
			parts.add(CompletableFuture.supplyAsync(() -> {
				try (TraceReader trace = openTrace(tracePath, shard)) {
					return Replayer.run(trace, target, r -> { }, replayOptions, base);
				}
				catch (IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			}, command -> Thread.ofPlatform().name("dbreplay-worker").start(command)));
		}
		List<ReplaySummary> summaries = new ArrayList<>();
		for (CompletableFuture<ReplaySummary> part : parts) {
			try {
				summaries.add(part.join());
			}
			catch (java.util.concurrent.CompletionException e) {
				if (e.getCause() instanceof java.io.UncheckedIOException io) {
					throw io.getCause();
				}
				throw e;
			}
		}
		return ReplaySummary.merge(summaries);
	}

	/** 고른 레코드 가운데 첫 문장의 시작 시각. 나눠 튼 조각들의 공통 기준이다. 없으면 0. */
	private static long firstStartMicros(String tracePath, Predicate<TraceRecord> selection) throws IOException {
		try (TraceReader trace = openTrace(tracePath, selection)) {
			TraceRecord first = trace.next();
			return first == null ? 0 : first.startMicros();
		}
	}

	/** DB에서 읽은 설정에, --profile-command가 있으면 그 명령이 낸 장비 설정을 더한다. */
	private static Map<String, String> profile(JdbcTarget target, Map<String, String> options) throws Exception {
		Map<String, String> profile = TargetProfile.read(target);
		String command = options.get("--profile-command");
		if (command != null) {
			profile.putAll(TargetProfile.readHost(command));
		}
		return profile;
	}

	/**
	 * 평가 때의 설정과 지금의 설정에서, 양쪽에 다 {@code host.} 항목이 있을 때만 그것을 남긴다. 한쪽에라도
	 * 없으면 양쪽에서 뺀다. 모르는 것을 "바뀌었다"고 하면 안 된다(하드웨어를 남기지 않고 평가했거나, 지금
	 * --profile-command를 주지 않았을 때다).
	 *
	 * @return 첫째가 평가 때의 설정, 둘째가 지금의 설정
	 */
	static List<Map<String, String>> comparableProfiles(Map<String, String> evaluated, Map<String, String> current) {
		boolean evaluatedHasHost = evaluated.keySet().stream().anyMatch(key -> key.startsWith(TargetProfile.HOST_PREFIX));
		boolean currentHasHost = current.keySet().stream().anyMatch(key -> key.startsWith(TargetProfile.HOST_PREFIX));
		if (evaluatedHasHost && currentHasHost) {
			return List.of(evaluated, current);
		}
		return List.of(dropHost(evaluated), dropHost(current));
	}

	private static Map<String, String> dropHost(Map<String, String> profile) {
		Map<String, String> kept = new TreeMap<>(profile);
		kept.keySet().removeIf(key -> key.startsWith(TargetProfile.HOST_PREFIX));
		return kept;
	}

	/** 이 평가를 지금도 믿어도 되는지 본다. 무효인 이유들을 돌려주고, 비어 있으면 유효하다. */
	private static List<String> invalidReasons(Evaluation evaluation, Map<String, String> options) throws Exception {
		return invalidReasons(evaluation, options, null);
	}

	/**
	 * @param currentProfile 지금의 설정. null이면 여기서 읽는다. check는 설정 명령을 한 번만 실행하려고 미리
	 *                       읽어 넘긴다(하드웨어 문구에도 같은 값을 쓴다)
	 */
	private static List<String> invalidReasons(Evaluation evaluation, Map<String, String> options,
			Map<String, String> currentProfile) throws Exception {
		TrafficModel current = null;
		String tracePath = options.get("--trace");
		if (tracePath != null) {
			try (TraceReader trace = openTrace(tracePath)) {
				current = TrafficModel.of(trace);
			}
		}
		Evaluation safe = withoutRawCommand(evaluation);
		Map<String, String> now = currentProfile != null ? currentProfile : profile(target(options), options);
		// 하드웨어는 양쪽에 다 있을 때만 비교한다. 뺀 맵으로 평가 쪽도 다시 만들어 같은 기준으로 견준다
		List<Map<String, String>> profiles = comparableProfiles(safe.profile(), now);
		return withProfile(safe, profiles.get(0)).invalidReasons(Instant.now(), profiles.get(1), alarmSettings(options),
				current, number(options, "--max-traffic-distance", 0.2));
	}

	/**
	 * check의 마지막 줄에 찍을 하드웨어 문구. 옵션을 줬는지가 아니라 실제로 비교했는지로 정한다. 설정 명령이
	 * 0으로 끝나면서 아무 값도 내지 않으면 지금 쪽에 {@code host.} 항목이 없어 비교하지 않는데, 옵션이 있다고
	 * "비교했다"고 찍으면 틀린 안심을 준다.
	 */
	static String hardwareNote(Map<String, String> evaluated, Map<String, String> current, boolean commandGiven) {
		if (hasHost(evaluated) && hasHost(current)) {
			return "하드웨어는 설정 명령이 낸 값으로 비교했다";
		}
		if (!hasHost(evaluated)) {
			return "하드웨어 사양은 DB 접속으로 알 수 없어 확인하지 않았다";
		}
		if (commandGiven) {
			return "평가 파일에 하드웨어가 있지만 --profile-command가 아무 값도 내지 않아 비교하지 않았다";
		}
		return "평가 파일에 하드웨어가 있지만 --profile-command를 주지 않아 비교하지 않았다";
	}

	private static boolean hasHost(Map<String, String> profile) {
		return profile.keySet().stream().anyMatch(key -> key.startsWith(TargetProfile.HOST_PREFIX));
	}

	/** 같은 평가에 설정 맵만 바꿔 끼운다. comparableProfiles가 뺀 맵을 평가 쪽에도 적용하려는 것이다. */
	private static Evaluation withProfile(Evaluation e, Map<String, String> profile) {
		return new Evaluation(e.evaluatedAt(), e.validDays(), profile, e.alarms(), e.unit(), e.traffic(), e.ceilingSpeed(),
				e.stoppedBy(), e.ceilingQps(), e.currentQps(), e.low(), e.high(), e.ceilingSpeeds(), e.explorations(),
				e.replica());
	}

	private static void manage(Map<String, String> options) throws Exception {
		ManagedTargets managed = JSON.readValue(Path.of(required(options, "--targets")).toFile(), ManagedTargets.class);
		boolean force = "yes".equals(options.get("--force"));
		Map<String, ManagedTargets.Action> actions = new java.util.LinkedHashMap<>();
		Map<String, String> why = new HashMap<>();

		for (ManagedTargets.Target t : managed.targets()) {
			Map<String, String> targetOptions = new HashMap<>(t.options());
			targetOptions.put("--out", t.evaluation());
			if (options.containsKey("--max-traffic-distance")) {
				targetOptions.put("--max-traffic-distance", options.get("--max-traffic-distance"));
			}
			Path file = Path.of(t.evaluation());
			ManagedTargets.Action action;
			try {
				if (!Files.exists(file)) {
					action = ManagedTargets.Action.EVALUATED;
					why.put(t.name(), "평가한 적이 없다");
				}
				else {
					List<String> reasons = force ? List.of("--force") : invalidReasons(JSON.readValue(file.toFile(), Evaluation.class), targetOptions);
					action = reasons.isEmpty() ? ManagedTargets.Action.KEPT : ManagedTargets.Action.REEVALUATED;
					why.put(t.name(), String.join(" / ", reasons));
				}
				if (action != ManagedTargets.Action.KEPT) {
					System.out.printf("%n=== %s: %s ===%n", t.name(), why.get(t.name()));
					explore(targetOptions);
				}
			}
			catch (Exception e) {
				// 한 대상이 실패해도 나머지는 계속 본다. 실패한 이유는 종류만 적는다. 메시지에 접속 정보가 섞일 수 있다
				action = ManagedTargets.Action.FAILED;
				why.put(t.name(), e.getClass().getSimpleName());
			}
			actions.put(t.name(), action);
		}

		System.out.printf("%n%-20s %-12s %8s %12s %6s %-6s  %s%n", "대상", "이번에 한 일", "상한", "상한(문장/초)", "수위", "판정", "평가한 시각");
		for (ManagedTargets.Target t : managed.targets()) {
			String did = switch (actions.get(t.name())) {
				case KEPT -> "그대로";
				case EVALUATED -> "평가";
				case REEVALUATED -> "다시 평가";
				case FAILED -> "실패";
			};
			Path file = Path.of(t.evaluation());
			if (!Files.exists(file)) {
				System.out.printf("%-20s %-12s %8s %12s %6s %-6s  %s%n", t.name(), did, "-", "-", "-", "-", why.get(t.name()));
				continue;
			}
			Evaluation e = JSON.readValue(file.toFile(), Evaluation.class);
			// 되풀이한 평가면 평가한 시각 뒤에 상한의 범위를 붙인다. 한 번 돌린 평가면 지금과 같다
			CeilingRange range = e.ceilingRange();
			String rangeSuffix = range == null ? ""
					: String.format(" (범위 %.2f~%.2f, %d번)", range.min(), range.max(), range.count());
			System.out.printf("%-20s %-12s %8.2f %12.0f %5.0f%% %-6s  %s%n", t.name(), did, e.ceilingSpeed(), e.ceilingQps(),
					e.level().level() * 100, switch (e.level().verdict()) {
						case SURPLUS -> "과잉";
						case HEALTHY -> "적정";
						case SHORT -> "부족";
					}, e.evaluatedAt() + (actions.get(t.name()) == ManagedTargets.Action.FAILED ? " (" + why.get(t.name()) + ")" : "") + rangeSuffix);
		}
		if (actions.containsValue(ManagedTargets.Action.FAILED)) {
			System.exit(1);
		}
	}

	private static void check(Map<String, String> options) throws Exception {
		// explore와 같은 검사를 한다. 잘못 준 수는 평가 파일을 읽거나 붙기 전에 거른다
		checkQpsOptions(options);
		Evaluation evaluation = JSON.readValue(Path.of(required(options, "--evaluation")).toFile(), Evaluation.class);
		String tracePath = options.get("--trace");
		// 지금의 설정을 한 번만 읽어 무효 사유와 하드웨어 문구에 함께 쓴다. 설정 명령을 두 번 실행하지 않는다
		Map<String, String> currentProfile = profile(target(options), options);
		List<String> reasons = invalidReasons(evaluation, options, currentProfile);

		System.out.printf("평가: %s, 상한 %.2f배속 (초당 %.0f문장), 수위 %.0f%%%n", evaluation.evaluatedAt(),
				evaluation.ceilingSpeed(), evaluation.ceilingQps(), evaluation.level().level() * 100);
		// 평가 파일의 상한은 그대로 두고, 지금의 QPS를 주면 수위만 다시 계산해 보여 준다
		if (options.containsKey("--current-qps")) {
			System.out.println(currentQpsLine(evaluation.ceilingQps(), number(options, "--current-qps", 0),
					evaluation.low(), evaluation.high()));
		}
		// 운영 DB에서 직접 잰다. 글의 수위는 운영 QPS ÷ 상한이다(원문 대조 51, #82)
		if (options.containsKey("--measure-url")) {
			long seconds = (long) number(options, "--measure-seconds", 60);
			double measured = measureQps(measureTarget(options), seconds);
			System.out.printf("  운영 DB에서 %d초 동안 잰 초당 문장 수: %.0f%n", seconds, measured);
			System.out.println(currentQpsLine(evaluation.ceilingQps(), measured, evaluation.low(), evaluation.high()));
		}
		// 종 DB를 함께 틀어 평가했으면 그 수위도 보여 준다. --current-replica-qps를 주면 다시 계산한다
		Evaluation.Replica replica = evaluation.replica();
		if (replica != null) {
			System.out.printf("  종 DB: 상한 초당 %.0f, 수위 %.0f%%%n", replica.ceilingQps(),
					replica.level(evaluation.low(), evaluation.high()).level() * 100);
			if (options.containsKey("--current-replica-qps")) {
				System.out.println(replicaLevelLine(number(options, "--current-replica-qps", 0), replica.ceilingQps(),
						evaluation.low(), evaluation.high()));
			}
		}
		// 되풀이한 평가면 위의 상한이 범위의 최솟값이라는 것을 밝힌다. 한 번 돌린 평가면 지금과 같다
		CeilingRange range = evaluation.ceilingRange();
		if (range != null) {
			System.out.printf("  되풀이한 탐색 %d번의 상한: 최소 %.2f, 중앙값 %.2f, 최대 %.2f배속. 위의 상한은 가장 낮은 값이다%n",
					range.count(), range.min(), range.median(), range.max());
		}
		if (reasons.isEmpty()) {
			System.out.println("유효하다");
		}
		else {
			System.out.println("무효다. 다시 평가해야 한다");
			reasons.forEach(r -> System.out.println("  - " + r));
		}
		// 하드웨어는 DB 접속으로 알 수 없다. 실제로 비교했는지로 문구를 정한다(옵션을 줬는지가 아니다)
		String hardware = hardwareNote(evaluation.profile(), currentProfile, options.containsKey("--profile-command"));
		System.out.println(hardware
				+ (tracePath == null ? ". 트래픽 모양은 --trace를 주지 않아 확인하지 않았다" : ""));
		if (!reasons.isEmpty()) {
			System.exit(1);
		}
	}

	/** 평가 파일에 적어 두고 나중에 바뀌었는지 볼 알람 설정. */
	private static Map<String, String> alarmSettings(Map<String, String> options) {
		Map<String, String> settings = new TreeMap<>();
		for (String name : List.of("--alarm-metric", "--alarm-active-sessions", "--alarm-total")) {
			if (options.containsKey(name)) {
				settings.put(name.substring(2), options.get(name));
			}
		}
		if (options.containsKey("--alarm-command")) {
			// 명령에는 암호가 들어 있을 수 있다. 바뀌었는지만 알면 되므로 지문만 적는다
			settings.put("alarm-command", fingerprint(options.get("--alarm-command")));
		}
		if (options.containsKey("--alarm-active-sessions") || options.containsKey("--alarm-metric")) {
			settings.put("alarm-sustain", options.getOrDefault("--alarm-sustain", "3"));
		}
		// slow_finished_total은 --slow-ms를 기준으로 세므로 --alarm-total을 줬을 때도 남긴다
		if (options.containsKey("--alarm-active-sessions") || options.containsKey("--alarm-metric")
				|| options.containsKey("--alarm-total")) {
			settings.put("slow-ms", options.getOrDefault("--slow-ms", "100"));
		}
		return settings;
	}

	/**
	 * 예전 평가 파일에는 알람 확인 명령의 원문이 들어 있다. 비교하기 전에 지문으로 바꿔서, 달라졌다고 알릴 때
	 * 원문이 화면에 찍히지 않게 한다.
	 */
	static Evaluation withoutRawCommand(Evaluation e) {
		String command = e.alarms().get("alarm-command");
		if (command == null || command.startsWith("sha256:")) {
			return e;
		}
		Map<String, String> alarms = new TreeMap<>(e.alarms());
		alarms.put("alarm-command", fingerprint(command));
		return new Evaluation(e.evaluatedAt(), e.validDays(), e.profile(), alarms, e.unit(), e.traffic(), e.ceilingSpeed(),
				e.stoppedBy(), e.ceilingQps(), e.currentQps(), e.low(), e.high(), e.ceilingSpeeds(), e.explorations(),
				e.replica());
	}

	static String fingerprint(String text) {
		try {
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return "sha256:" + java.util.HexFormat.of().formatHex(digest, 0, 8);
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static RoundJudge judge(Map<String, String> options) {
		List<Alarm> alarms = new ArrayList<>();
		if (options.containsKey("--alarm-command")) {
			alarms.add(Alarm.command(options.get("--alarm-command")));
		}
		int sustain = (int) number(options, "--alarm-sustain", 3);
		if (options.containsKey("--alarm-active-sessions")) {
			alarms.add(Alarm.metric(DbMetricSource.ACTIVE_SESSIONS, number(options, "--alarm-active-sessions", 0), sustain));
		}
		if (options.containsKey("--alarm-metric")) {
			alarms.addAll(Alarm.metrics(options.get("--alarm-metric"), sustain));
		}
		if (options.containsKey("--alarm-total")) {
			alarms.addAll(Alarm.totals(options.get("--alarm-total")));
		}
		if (alarms.isEmpty()) {
			System.err.println("과부하는 알람으로 판정한다. --alarm-command, --alarm-metric, --alarm-total, --alarm-active-sessions 중 하나는 줘야 한다");
			System.exit(2);
		}
		return new RoundJudge(alarms, number(options, "--max-distortion", 0.2));
	}

	private static void run(String command) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		int exit = process.waitFor();
		if (exit != 0) {
			// 명령에는 암호가 들어 있을 수 있다. 메시지에 싣지 않는다
			throw new IOException("되돌리기 명령이 실패했다 (종료 코드 " + exit + ")");
		}
	}

	private static void print(ReplaySummary s, Replayer.Options options) {
		double planned = s.traceSpanMicros() / s.speed();
		System.out.printf("문장 %d개를 연결 %d개로 %.1f배속에 틀었다 (단위: %s)%n", s.statements(), s.sessions(), s.speed(),
				switch (options.unit()) {
					case TRANSACTION -> "트랜잭션";
					case STATEMENT -> "문장";
					case SESSION -> "연결";
				});
		if (s.skipped() > 0) {
			System.out.printf("건너뛴 문장: %d개 (연결을 돌려 쓸 때 뜻이 없는 세션 설정과 트랜잭션 제어)%n", s.skipped());
		}
		System.out.printf("걸린 시간: %.2f초 (원본 구간 %.2f초를 배속으로 나누면 %.2f초)%n",
				s.elapsedMicros() / 1e6, s.traceSpanMicros() / 1e6, planned / 1e6);
		System.out.printf("오류: 원본 %d개, 재생 %d개%n", s.origErrors(), s.errors());
		System.out.printf("계획보다 %.1fms 넘게 늦은 문장: %d개 (%.2f%%)%n",
				options.lateThresholdMicros() / 1e3, s.late(), s.statements() == 0 ? 0 : 100.0 * s.late() / s.statements());
		System.out.printf("  재생기가 늦어서: %.2f%%   <- 재생의 왜곡%n", s.replayerLateRatio() * 100);
		System.out.printf("  같은 단위의 앞 문장이 안 끝나서: %.2f%%   <- 문장의 왕복이 원본보다 오래 걸린 구간%n", s.sessionBusyRatio() * 100);
		System.out.printf("늦은 정도: 중앙값 %s, p99 %s, 최대 %s%n",
				ms(s.lag().quantile(0.5)), ms(s.lag().quantile(0.99)), ms(s.lag().max()));
		System.out.printf("실행 시간 중앙값: 원본 %s, 재생 %s%n",
				ms(s.origDuration().quantile(0.5)), ms(s.duration().quantile(0.5)));
		System.out.printf("실행 시간 p99:    원본 %s, 재생 %s%n",
				ms(s.origDuration().quantile(0.99)), ms(s.duration().quantile(0.99)));
	}

	private static Replayer.Options replayOptions(Map<String, String> options, double speed) {
		Replayer.Options defaults = Replayer.Options.atSpeed(speed);
		Replayer.Options chosen = readsOnly(options) ? defaults.onlyReads()
				: writesOnly(options) ? defaults.withUnit(Replayer.Unit.TRANSACTION) : defaults.withUnit(unit(options));
		return chosen.withMaxConnections((int) number(options, "--max-connections", defaults.maxConnections()));
	}

	private static Replayer.Unit unit(Map<String, String> options) {
		return Replayer.Unit.valueOf(options.getOrDefault("--unit", "transaction").toUpperCase(Locale.ROOT));
	}

	private static JdbcTarget target(Map<String, String> options) {
		String password = options.getOrDefault("--password", System.getenv().getOrDefault("DBREPLAY_PASSWORD", ""));
		return new JdbcTarget(required(options, "--url"), required(options, "--user"), password);
	}

	/** 운영 DB. 계정은 --measure-user, 없으면 --user. 암호는 명령줄에 남지 않게 환경 변수로 받는다. */
	private static JdbcTarget measureTarget(Map<String, String> options) {
		String password = System.getenv().getOrDefault("DBREPLAY_MEASURE_PASSWORD",
				options.getOrDefault("--password", System.getenv().getOrDefault("DBREPLAY_PASSWORD", "")));
		return new JdbcTarget(options.get("--measure-url"), options.getOrDefault("--measure-user", required(options, "--user")),
				password);
	}

	/** 운영 DB의 초당 문장 수를 그 시간 동안 떠서 평균을 낸다. 표본기 자신의 조회는 뺀 값이다. */
	private static double measureQps(JdbcTarget production, long seconds) throws Exception {
		Map<String, double[]> metrics;
		List<String> failures;
		try (MetricSampler sampler = new MetricSampler().add(new DbMetricSource(production, 100), 1_000)) {
			Thread.sleep(seconds * 1_000);
			metrics = sampler.stop();
			failures = sampler.failures();
		}
		requireNoFailures(failures);
		return meanQps(metrics, production.isPostgres());
	}

	/** 뜬 qps의 평균. 없으면 왜 없는지 알린다. 지어내지 않는다. */
	static double meanQps(Map<String, double[]> metrics, boolean postgres) {
		double[] qps = metrics.get(DbMetricSource.QPS);
		if (qps == null || qps.length == 0) {
			throw new IllegalStateException(postgres
					? "운영 DB에서 초당 문장 수를 잴 수 없다. PostgreSQL은 pg_stat_statements 확장이 있어야 한다"
					: "운영 DB에서 초당 문장 수를 잴 수 없다. 재는 시간을 늘리거나 performance_schema 권한을 본다");
		}
		return java.util.Arrays.stream(qps).average().orElseThrow();
	}

	/** 종 DB. --replica-url을 주지 않았으면 null. 계정과 암호를 따로 주지 않으면 주 DB의 것을 쓴다. */
	private static JdbcTarget replica(Map<String, String> options) {
		String url = options.get("--replica-url");
		if (url == null) {
			return null;
		}
		String password = options.getOrDefault("--replica-password",
				options.getOrDefault("--password", System.getenv().getOrDefault("DBREPLAY_PASSWORD", "")));
		return new JdbcTarget(url, options.getOrDefault("--replica-user", required(options, "--user")), password);
	}

	/** 종 DB의 트래픽은 글대로 문장 하나씩 독립으로 내보낸다. */
	private static Replayer.Options replicaOptions(Map<String, String> options, double speed) {
		Replayer.Options defaults = Replayer.Options.atSpeed(speed);
		return defaults.withUnit(Replayer.Unit.STATEMENT)
				.withMaxConnections((int) number(options, "--max-connections", defaults.maxConnections()));
	}

	/** 종 DB의 트래픽을 주 DB의 재생과 나란히 튼다. 두 재생은 각자의 첫 문장을 기준으로 시각을 맞춘다. */
	private static final class ReplicaRun {

		private final CompletableFuture<ReplaySummary> summary;

		private ReplicaRun(CompletableFuture<ReplaySummary> summary) {
			this.summary = summary;
		}

		/** @return --replica-trace를 주지 않았으면 null */
		static ReplicaRun start(Map<String, String> options, double speed) {
			String tracePath = options.get("--replica-trace");
			if (tracePath == null) {
				return null;
			}
			JdbcTarget replica = replica(options);
			if (replica == null) {
				throw new IllegalArgumentException("--replica-trace에는 --replica-url이 있어야 한다");
			}
			Replayer.Options replicaOptions = replicaOptions(options, speed);
			return new ReplicaRun(CompletableFuture.supplyAsync(() -> {
				try (TraceReader trace = openTrace(tracePath)) {
					return Replayer.run(trace, replica, r -> { }, replicaOptions);
				}
				catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}, task -> Thread.ofPlatform().name("dbreplay-replica").start(task)));
		}

		ReplaySummary join() {
			return summary.join();
		}
	}

	private static TraceReader openTrace(String path) throws IOException {
		return new TraceReader(Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8));
	}

	private static TraceReader openTrace(String path, Predicate<TraceRecord> keep) throws IOException {
		return new TraceReader(Files.newBufferedReader(Path.of(path), StandardCharsets.UTF_8), keep);
	}

	private static double number(Map<String, String> options, String name, double fallback) {
		String value = options.get(name);
		return value == null ? fallback : Double.parseDouble(value);
	}

	private static String ms(double micros) {
		return String.format("%.3fms", micros / 1e3);
	}

	/** 쉼표로 나눈 이름들. 없으면 빈 집합. 이름 앞뒤의 공백은 뗀다 */
	static Set<String> names(String value) {
		if (value == null) {
			return Set.of();
		}
		Set<String> names = new java.util.TreeSet<>();
		for (String name : value.split(",")) {
			if (!name.isBlank()) {
				names.add(name.strip());
			}
		}
		return names;
	}

	private static String required(Map<String, String> options, String name) {
		String value = options.get(name);
		if (value == null) {
			usage();
		}
		return value;
	}

	private static void usage() {
		System.err.print(USAGE);
		System.exit(2);
	}
}
