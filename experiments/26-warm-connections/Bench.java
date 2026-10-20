import io.dbreplay.replay.Histogram;
import io.dbreplay.replay.JdbcTarget;
import io.dbreplay.replay.ReplaySummary;
import io.dbreplay.replay.Replayer;
import io.dbreplay.replay.ResultSink;
import io.dbreplay.replay.Target;
import io.dbreplay.replay.TargetSession;
import io.dbreplay.trace.TraceReader;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.LongAdder;

/**
 * 미리 여는 연결 수를 바꿔 가며 진짜 MySQL에 읽기만 틀고 늦은 비율을 잰다(#126). 실험 24의 측정 도구에 미리 여는 수만 더했다.
 *
 * <p>쓰는 법: java -cp '/app/lib/*:classes' Bench <트래픽 파일> <JDBC URL> <계정> <반복 수> <배속> <미리 여는 수>...
 * 4배속을 미리 여는 수 16으로 한 번 데운 뒤(반복 0), 미리 여는 수를 차례로 바꾼 한 바퀴를 반복 수만큼 돈다.
 * 줄마다 "미리여는수 반복 늦은비율 도중에연연결 연결여는µs중앙값 미리열기와재생을합친초 재생초"를 찍는다.
 */
public final class Bench {

	private static final class Counting implements Target {
		final Target inner;
		final ThreadLocal<Long> openedMicros = new ThreadLocal<>();

		Counting(Target inner) {
			this.inner = inner;
		}

		@Override
		public TargetSession open(String db) throws Exception {
			long t0 = System.nanoTime();
			try {
				return inner.open(db);
			}
			finally {
				openedMicros.set((System.nanoTime() - t0) / 1_000);
			}
		}
	}

	public static void main(String[] args) throws Exception {
		Path trace = Path.of(args[0]);
		Counting target = new Counting(new JdbcTarget(args[1], args[2], System.getenv("DBREPLAY_PASSWORD")));
		int repeat = Integer.parseInt(args[3]);
		double speed = Double.parseDouble(args[4]);
		round(trace, target, 4, 16, 0);
		for (int i = 1; i <= repeat; i++) {
			for (int a = 5; a < args.length; a++) {
				round(trace, target, speed, Integer.parseInt(args[a]), i);
			}
		}
	}

	private static void round(Path trace, Counting target, double speed, int warm, int cycle) throws Exception {
		LongAdder opened = new LongAdder();
		Histogram openMicros = new Histogram();
		ResultSink sink = r -> {
			Long took = target.openedMicros.get();
			if (took != null) {
				target.openedMicros.remove();
				opened.increment();
				openMicros.record(took);
			}
		};
		long t0 = System.nanoTime();
		ReplaySummary s;
		try (Reader in = Files.newBufferedReader(trace, StandardCharsets.UTF_8); TraceReader reader = new TraceReader(in)) {
			s = Replayer.run(reader, target, sink, Replayer.Options.atSpeed(speed).onlyReads().withWarmConnections(warm));
		}
		double wall = (System.nanoTime() - t0) / 1e9;
		System.out.printf("%d %d %.4f %d %d %.2f %.2f%n", warm, cycle, s.replayerLateRatio(), opened.sum(), openMicros.quantile(0.5),
				wall, s.elapsedMicros() / 1e6);
	}
}
