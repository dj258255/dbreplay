package io.dbreplay.convert;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

import io.dbreplay.trace.TraceHeader;
import io.dbreplay.trace.TraceRecord;
import io.dbreplay.trace.TraceWriter;

/**
 * 변환 중인 레코드를 모았다가 시작 시각 순으로 쓴다.
 *
 * <p>서버 로그는 문장이 끝난 순서로 적히고 재생은 시작 순서가 필요하다. 지금은 전부 메모리에 올려
 * 정렬하므로 메모리에 들어가는 크기까지만 다룬다.
 */
public final class RecordBuffer {

	/** @param sessions 문장이나 종료가 하나라도 쓰인 연결 수 */
	public record Written(long statements, long closes, long sessions) {
	}

	private final List<TraceRecord> records = new ArrayList<>();
	private final BitSet dropped = new BitSet();

	/** @return 뒤에 {@link #drop}으로 가리킬 위치 */
	public int add(TraceRecord record) {
		records.add(record);
		return records.size() - 1;
	}

	public void drop(int index) {
		dropped.set(index);
	}

	public int droppedCount() {
		return dropped.cardinality();
	}

	public Written write(Writer out, TraceHeader header) throws IOException {
		List<TraceRecord> kept = new ArrayList<>(records.size());
		for (int i = 0; i < records.size(); i++) {
			if (!dropped.get(i)) {
				kept.add(records.get(i));
			}
		}
		// 안정 정렬이라 시각이 같은 문장은 넣은 순서를 지킨다. 한 연결 안의 순서가 여기에 달려 있다.
		kept.sort(Comparator.comparingLong(TraceRecord::startMicros));

		long statements = 0;
		long closes = 0;
		try (TraceWriter writer = new TraceWriter(out, header)) {
			for (TraceRecord r : kept) {
				writer.write(r);
				if (r.endsSession()) {
					closes++;
				}
				else {
					statements++;
				}
			}
		}
		return new Written(statements, closes, kept.stream().map(TraceRecord::session).distinct().count());
	}
}
