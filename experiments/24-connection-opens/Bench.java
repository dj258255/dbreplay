import io.dbreplay.replay.Histogram;
import io.dbreplay.replay.JdbcTarget;
import io.dbreplay.replay.ReplayResult;
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
 * 진짜 MySQL에 읽기만 틀면서 늦은 문장이 그 자리에서 새 연결을 열었는지 센다(#119). 재생기 쪽 코드는 그대로 부른다.
 *
 * <p>문장 단위 재생에서는 연결 받기(풀이 비면 여기서 {@link Target#open}), 실행, 결과 받기({@link ResultSink})가 한 가상 스레드에서
 * 차례로 일어난다(`Replayer.runAlone`). 그래서 open이 그 스레드에 남긴 표시를 결과 받기가 읽으면 그 문장이 새 연결을 열었는지 안다.
 * 시작하기 전에 미리 여는 연결은 넘겨주는 스레드에서 열리고, 그 스레드는 결과를 받지 않으므로 세지 않는다.
 *
 * <p>쓰는 법: java -cp '/app/lib/*:classes' Bench <트래픽 파일> <JDBC URL> <계정> <반복 수> <데우기 배속> <배속>...
 * 암호는 DBREPLAY_PASSWORD에서 읽는다. 줄마다
 * "배속 반복 문장수 늦은비율 늦은수 그중새연결 새연결수 연결여는µs중앙값 연결여는µs최대 새연결없이늦은문장의늦음p99µs 걸린초"를 찍는다.
 */
public final class Bench {

	private static final long LATE_MICROS = 1_000; // Replayer.Options.atSpeed의 lateThresholdMicros와 같다

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
		round(trace, target, Double.parseDouble(args[4]), 0);
		for (int i = 1; i <= repeat; i++) {
			for (int a = 5; a < args.length; a++) {
				round(trace, target, Double.parseDouble(args[a]), i);
			}
		}
	}

	private static void round(Path trace, Counting target, double speed, int cycle) throws Exception {
		LongAdder late = new LongAdder(), lateOpened = new LongAdder(), opened = new LongAdder();
		Histogram openMicros = new Histogram(), lagNotOpened = new Histogram();
		ResultSink sink = (ReplayResult r) -> {
			Long took = target.openedMicros.get();
			if (took != null) {
				target.openedMicros.remove();
				opened.increment();
				openMicros.record(took);
			}
			if (r.lagMicros() > LATE_MICROS && !r.sessionBusy()) {
				late.increment();
				if (took != null) {
					lateOpened.increment();
				}
			}
			if (took == null) {
				lagNotOpened.record(r.lagMicros());
			}
		};
		ReplaySummary s;
		try (Reader in = Files.newBufferedReader(trace, StandardCharsets.UTF_8); TraceReader reader = new TraceReader(in)) {
			s = Replayer.run(reader, target, sink, Replayer.Options.atSpeed(speed).onlyReads());
		}
		System.out.printf("%s %d %d %.4f %d %d %d %d %d %d %.2f%n", fmt(speed), cycle, s.statements(), s.replayerLateRatio(), late.sum(),
				lateOpened.sum(), opened.sum(), openMicros.quantile(0.5), openMicros.max(), lagNotOpened.quantile(0.99),
				s.elapsedMicros() / 1e6);
	}

	private static String fmt(double speed) {
		return speed == Math.rint(speed) ? String.valueOf((long) speed) : String.valueOf(speed);
	}
}
