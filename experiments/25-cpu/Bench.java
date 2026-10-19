import io.dbreplay.replay.JdbcTarget;
import io.dbreplay.replay.ReplaySummary;
import io.dbreplay.replay.Replayer;
import io.dbreplay.trace.TraceReader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 진짜 MySQL에 읽기만 틀면서 도커 장비의 CPU 사용량을 뜬다(#123). 재생기 쪽 코드는 그대로 부른다.
 *
 * <p>컨테이너 안의 /proc/stat은 그 컨테이너의 값만 보여 준다. 그래서 호스트의 cgroup을 붙여(--cgroupns=host) 루트의 cpu.stat으로
 * 장비 전체를, 컨테이너마다의 cpu.stat으로 MySQL과 재생기를 센다. 50ms마다 읽고, 재생 구간의 처음과 끝에 가장 가까운 표본끼리 뺀다.
 *
 * <p>쓰는 법: java -cp '/app/lib/*:classes' Bench <트래픽 파일> <JDBC URL> <계정> <cgroup 뿌리> <MySQL cgroup> <반복 수> <데우기 배속> <배속>...
 * 암호는 DBREPLAY_PASSWORD에서 읽는다. 줄마다 "배속 반복 늦은비율 장비전체% MySQL코어 재생기코어 구간초"를 찍는다.
 */
public final class Bench {

	private static final long LOOKAHEAD_NANOS = 1_000_000_000L; // Replayer.Options.atSpeed의 lookaheadMicros와 같다
	private static final int CORES = Runtime.getRuntime().availableProcessors();

	private record Sample(long nanos, long total, long mysql, long self) {
	}

	private static final List<Sample> samples = new ArrayList<>();

	public static void main(String[] args) throws Exception {
		Path trace = Path.of(args[0]);
		JdbcTarget target = new JdbcTarget(args[1], args[2], System.getenv("DBREPLAY_PASSWORD"));
		Path root = Path.of(args[3]);
		Path mysql = root.resolve(args[4]).resolve("cpu.stat");
		Path self = root.resolve(Files.readString(Path.of("/proc/self/cgroup")).trim().replaceFirst("^0::/", "")).resolve("cpu.stat");
		Path total = root.resolve("cpu.stat");
		int repeat = Integer.parseInt(args[5]);

		Thread.ofPlatform().daemon().name("cpu-sampler").start(() -> {
			while (true) {
				try {
					Sample s = new Sample(System.nanoTime(), usage(total), usage(mysql), usage(self));
					synchronized (samples) {
						samples.add(s);
					}
					Thread.sleep(50);
				}
				catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}
		});

		round(trace, target, Double.parseDouble(args[6]), 0);
		for (int i = 1; i <= repeat; i++) {
			for (int a = 7; a < args.length; a++) {
				round(trace, target, Double.parseDouble(args[a]), i);
			}
		}
	}

	private static long usage(Path cpuStat) throws IOException {
		for (String line : Files.readAllLines(cpuStat)) {
			if (line.startsWith("usage_usec ")) {
				return Long.parseLong(line.substring("usage_usec ".length()).trim());
			}
		}
		throw new IOException("usage_usec가 없다: " + cpuStat);
	}

	private static void round(Path trace, JdbcTarget target, double speed, int cycle) throws Exception {
		long started = System.nanoTime();
		ReplaySummary s;
		try (Reader in = Files.newBufferedReader(trace, StandardCharsets.UTF_8); TraceReader reader = new TraceReader(in)) {
			s = Replayer.run(reader, target, r -> { }, Replayer.Options.atSpeed(speed).onlyReads());
		}
		Thread.sleep(100); // 끝 쪽 표본이 들어올 때까지
		long from = started + LOOKAHEAD_NANOS;
		long to = from + Math.round(s.traceSpanMicros() * 1_000.0 / speed);
		Sample a = nearest(from), b = nearest(to);
		double secs = (b.nanos - a.nanos) / 1e9;
		double totalPct = (b.total - a.total) / 1e6 / secs / CORES * 100;
		double mysqlCores = (b.mysql - a.mysql) / 1e6 / secs;
		double selfCores = (b.self - a.self) / 1e6 / secs;
		System.out.printf("%s %d %.4f %.1f %.2f %.2f %.2f%n", fmt(speed), cycle, s.replayerLateRatio(), totalPct, mysqlCores, selfCores, secs);
	}

	private static Sample nearest(long nanos) {
		synchronized (samples) {
			Sample best = samples.getFirst();
			for (Sample s : samples) {
				if (Math.abs(s.nanos - nanos) < Math.abs(best.nanos - nanos)) {
					best = s;
				}
			}
			return best;
		}
	}

	private static String fmt(double speed) {
		return speed == Math.rint(speed) ? String.valueOf((long) speed) : String.valueOf(speed);
	}
}
