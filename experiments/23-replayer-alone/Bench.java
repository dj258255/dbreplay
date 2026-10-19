import io.dbreplay.replay.ReplaySummary;
import io.dbreplay.replay.Replayer;
import io.dbreplay.replay.Target;
import io.dbreplay.replay.TargetSession;
import io.dbreplay.trace.TraceReader;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * DB 없이 재생기만 돌린다(#117). 대상은 문장을 받자마자 성공으로 돌려준다. 그러니 늦은 문장은 모두 재생기 안에서 생긴다.
 * 옵션은 explore --only reads가 쓰는 것과 같다(Options.atSpeed(s).onlyReads()).
 *
 * <p>쓰는 법: java -cp '/app/lib/*:classes' Bench <트래픽 파일> <반복 수> <배속>...
 * 줄마다 "배속 반복 문장수 늦은비율 늦음p99µs 걸린초"를 찍는다. 배속을 한 바퀴 돈 것을 반복 수만큼 되풀이한다.
 */
public final class Bench {

	private static final TargetSession NOTHING = new TargetSession() {
		@Override
		public int execute(String db, String sql) {
			return 0;
		}

		@Override
		public void close() {
		}
	};

	public static void main(String[] args) throws Exception {
		Path trace = Path.of(args[0]);
		int repeat = Integer.parseInt(args[1]);
		Target target = db -> NOTHING;
		for (int i = 1; i <= repeat; i++) {
			for (int a = 2; a < args.length; a++) {
				double speed = Double.parseDouble(args[a]);
				ReplaySummary s;
				try (Reader in = Files.newBufferedReader(trace, StandardCharsets.UTF_8); TraceReader reader = new TraceReader(in)) {
					s = Replayer.run(reader, target, r -> { }, Replayer.Options.atSpeed(speed).onlyReads());
				}
				System.out.printf("%s %d %d %.4f %d %.2f%n", args[a], i, s.statements(), s.replayerLateRatio(), s.lag().quantile(0.99),
						s.elapsedMicros() / 1e6);
			}
		}
	}
}
