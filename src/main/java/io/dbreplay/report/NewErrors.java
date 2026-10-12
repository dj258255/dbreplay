package io.dbreplay.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import io.dbreplay.replay.ReplayResult;
import io.dbreplay.replay.ResultSink;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;

/**
 * 원본에서는 실패하지 않았는데 재생에서 실패한 문장을 모은다. 버전을 올리거나 스키마를 바꾼 뒤 가장 먼저 보는 것이다.
 *
 * <p>재생 결과에는 문장 본문이 없고 트래픽 파일의 줄 순번만 있다. 재생하는 동안에는 순번과 오류 번호만 모으고,
 * 끝난 뒤 트래픽 파일을 한 번 더 읽어 본문을 찾아 오류 번호와 SQL 모양별로 묶는다.
 *
 * <p>원본의 성공 여부를 녹화가 알려 주지 않은 문장도 여기에 든다. 원본에서도 실패했는지는 알 수 없다.
 */
public final class NewErrors implements ResultSink {

	/** 순번을 기억해 둘 실패의 한도. 넘는 것은 개수만 센다. 전부 실패하는 재생에서 메모리가 문장 수만큼 들지 않게 한다 */
	public static final int LIMIT = 100_000;

	/** @param sample 그 모양의 문장 하나. 값이 그대로 들어 있다 */
	public record Row(int error, String shape, long count, String sample) {
	}

	private final ResultSink next;
	private final Map<Long, Integer> failed = new ConcurrentHashMap<>();
	private final LongAdder beyondLimit = new LongAdder();

	/** @param next 결과를 이어서 넘길 곳 */
	public NewErrors(ResultSink next) {
		this.next = next;
	}

	@Override
	public void accept(ReplayResult result) {
		if (result.error() != 0 && (result.origError() == null || result.origError() == 0)) {
			if (failed.size() < LIMIT) {
				failed.put(result.seq(), result.error());
			}
			else {
				beyondLimit.increment();
			}
		}
		next.accept(result);
	}

	public long count() {
		return failed.size() + beyondLimit.sum();
	}

	/** 한도를 넘어 모양을 찾지 않고 개수만 센 실패 수. */
	public long beyondLimit() {
		return beyondLimit.sum();
	}

	/** 트래픽 파일을 다시 읽어 실패한 문장을 오류 번호와 모양별로 묶는다. 많은 순이다. */
	public List<Row> rows(TraceReader trace) throws IOException {
		Map<String, Row> rows = new LinkedHashMap<>();
		long seq = 0;
		for (TraceRecord r; (r = trace.next()) != null; seq++) {
			Integer error = failed.get(seq);
			if (error == null || r.sql() == null) {
				continue;
			}
			String shape = SqlShape.of(r.sql());
			String sql = r.sql();
			rows.merge(error + " " + shape, new Row(error, shape, 1, sql),
					(old, one) -> new Row(old.error(), old.shape(), old.count() + 1, old.sample()));
		}
		List<Row> sorted = new ArrayList<>(rows.values());
		sorted.sort(Comparator.comparingLong(Row::count).reversed());
		return sorted;
	}
}
