package io.dbreplay.convert;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import io.dbreplay.trace.TraceHeader;
import io.dbreplay.trace.TraceRecord;
import io.dbreplay.trace.TraceWriter;

/**
 * 변환 중인 레코드를 모았다가 시작 시각 순으로 쓴다.
 *
 * <p>서버 로그는 문장이 끝난 순서로 적히고 재생은 시작 순서가 필요하다. 메모리에 정해진 만큼 모이면
 * 그만큼을 정렬해 임시 파일로 내리고, 끝에 임시 파일들을 병합한다(외부 병합 정렬). 한 번도 내리지
 * 않았으면 메모리에서 정렬해 바로 쓴다.
 *
 * <p>순서는 (시작 시각, 넣은 순번)이다. 시각이 같은 문장은 넣은 순서를 지킨다. 한 연결 안의 순서가
 * 여기에 달려 있다. 순서가 하나로 정해지므로 메모리를 얼마로 잡든 출력은 같다.
 */
public final class RecordBuffer implements Closeable {

	/** @param sessions 문장이나 종료가 하나라도 쓰인 연결 수 */
	public record Written(long statements, long closes, long sessions) {
	}

	private record Entry(long index, TraceRecord record) {
	}

	/** 한 번에 여는 임시 파일 수. 운영체제의 파일 수 한도(macOS의 기본값 256)보다 넉넉히 작게 둔다. */
	private static final int DEFAULT_FAN_IN = 64;
	private static final Comparator<Entry> BY_START = Comparator.comparingLong(e -> e.record().startMicros());

	private final Path tempParent;
	private final long budgetBytes;
	private final int fanIn;

	private List<Entry> chunk = new ArrayList<>();
	private long chunkBytes;
	private long next;
	private Path spillDir;
	private final List<Path> runs = new ArrayList<>();
	private long spills;
	/** 머리 정책으로 뺄 범위. 연결마다 [처음 순번, 끝 순번]의 목록이다 */
	private final Map<String, List<long[]>> droppedRanges = new HashMap<>();
	private long dropped;

	/** 힙의 4분의 1까지 메모리에 모으고, 넘치면 시스템의 임시 폴더로 내린다. */
	public RecordBuffer() {
		this(null, Runtime.getRuntime().maxMemory() / 4);
	}

	/**
	 * @param tempParent  임시 파일을 둘 폴더. null이면 시스템의 임시 폴더
	 * @param budgetBytes 메모리에 모아 둘 레코드의 어림 크기. 넘으면 임시 파일로 내린다
	 */
	public RecordBuffer(Path tempParent, long budgetBytes) {
		this(tempParent, budgetBytes, DEFAULT_FAN_IN);
	}

	RecordBuffer(Path tempParent, long budgetBytes, int fanIn) {
		if (budgetBytes < 1 || fanIn < 2) {
			throw new IllegalArgumentException("메모리는 1바이트 이상, 한 번에 병합할 파일은 2개 이상이어야 한다");
		}
		this.tempParent = tempParent;
		this.budgetBytes = budgetBytes;
		this.fanIn = fanIn;
	}

	/** @return 넣은 순번. 뒤에 {@link #dropRange}로 가리킨다 */
	public long add(TraceRecord record) throws IOException {
		if (record.session().indexOf('\t') >= 0 || record.session().indexOf('\n') >= 0) {
			throw new IllegalArgumentException("연결 식별자에 탭이나 줄바꿈이 있다");
		}
		long index = next++;
		chunk.add(new Entry(index, record));
		chunkBytes += estimate(record);
		if (chunkBytes >= budgetBytes) {
			spill();
		}
		return index;
	}

	/** 이 연결의 레코드 중 순번이 from 이상 to 이하인 것을 뺀다. 이미 임시 파일로 내려간 것도 뺀다. */
	public void dropRange(String session, long from, long to) {
		droppedRanges.computeIfAbsent(session, s -> new ArrayList<>()).add(new long[] { from, to });
	}

	/** {@link #write} 뒤에 부른다. */
	public long droppedCount() {
		return dropped;
	}

	/** 임시 파일로 내린 횟수. 0이면 메모리에서만 정렬했다. */
	public long spills() {
		return spills;
	}

	public Written write(Writer out, TraceHeader header) throws IOException {
		long statements = 0;
		long closes = 0;
		Set<String> sessions = new HashSet<>();
		try (TraceWriter writer = new TraceWriter(out, header)) {
			if (runs.isEmpty()) {
				chunk.sort(BY_START);
				for (Entry e : chunk) {
					TraceRecord r = e.record();
					if (isDropped(r.session(), e.index())) {
						dropped++;
						continue;
					}
					writer.write(r);
					sessions.add(r.session());
					if (r.endsSession()) {
						closes++;
					}
					else {
						statements++;
					}
				}
				chunk = new ArrayList<>();
				return new Written(statements, closes, sessions.size());
			}

			if (!chunk.isEmpty()) {
				spill();
			}
			// 한 번에 열 수 있는 수보다 많으면 앞의 것들부터 묶어 더 큰 파일로 만든다
			while (runs.size() > fanIn) {
				List<Path> group = new ArrayList<>(runs.subList(0, fanIn));
				Path merged = newRunFile();
				try (BufferedWriter w = Files.newBufferedWriter(merged, StandardCharsets.UTF_8)) {
					merge(group, line -> {
						w.write(line.text);
						w.write('\n');
					});
				}
				runs.subList(0, fanIn).clear();
				runs.add(merged);
			}
			long[] counts = new long[2];
			merge(new ArrayList<>(runs), line -> {
				String[] parts = line.text.split("\t", 5);
				String session = parts[3];
				if (isDropped(session, line.index)) {
					dropped++;
					return;
				}
				writer.writeSerialized(parts[4]);
				sessions.add(session);
				counts[parts[2].equals("c") ? 1 : 0]++;
			});
			runs.clear();
			return new Written(counts[0], counts[1], sessions.size());
		}
	}

	/** 남은 임시 파일을 지운다. 변환이 중간에 실패했을 때도 불려야 한다. */
	@Override
	public void close() throws IOException {
		if (spillDir == null) {
			return;
		}
		try (var files = Files.list(spillDir)) {
			for (Path file : files.toList()) {
				Files.deleteIfExists(file);
			}
		}
		Files.deleteIfExists(spillDir);
		spillDir = null;
	}

	private boolean isDropped(String session, long index) {
		List<long[]> ranges = droppedRanges.get(session);
		if (ranges == null) {
			return false;
		}
		for (long[] range : ranges) {
			if (index >= range[0] && index <= range[1]) {
				return true;
			}
		}
		return false;
	}

	/** 자바 객체로 들고 있을 때의 어림 크기. 문장 본문이 대부분이다. */
	private static long estimate(TraceRecord record) {
		return 320 + 2L * (record.sql() == null ? 0 : record.sql().length());
	}

	/** 메모리의 레코드를 정렬해 임시 파일 하나로 내린다. 한 줄은 "시작 시각, 순번, 종류, 연결, JSON"이고 탭으로 나눈다. */
	private void spill() throws IOException {
		chunk.sort(BY_START);
		Path run = newRunFile();
		try (BufferedWriter w = Files.newBufferedWriter(run, StandardCharsets.UTF_8)) {
			for (Entry e : chunk) {
				TraceRecord r = e.record();
				w.write(Long.toString(r.startMicros()));
				w.write('\t');
				w.write(Long.toString(e.index()));
				w.write(r.endsSession() ? "\tc\t" : "\ts\t");
				w.write(r.session());
				w.write('\t');
				w.write(TraceWriter.serialize(r));
				w.write('\n');
			}
		}
		runs.add(run);
		spills++;
		chunk = new ArrayList<>();
		chunkBytes = 0;
	}

	private Path newRunFile() throws IOException {
		if (spillDir == null) {
			spillDir = tempParent == null ? Files.createTempDirectory("dbreplay-convert-")
					: Files.createTempDirectory(tempParent, "dbreplay-convert-");
		}
		return Files.createTempFile(spillDir, "run-", ".tsv");
	}

	/** 임시 파일 하나를 앞에서부터 읽는다. 지금 줄의 시작 시각과 순번을 들고 있다. */
	private static final class Line implements Closeable {
		final BufferedReader in;
		String text;
		long start;
		long index;

		Line(Path file) throws IOException {
			in = Files.newBufferedReader(file, StandardCharsets.UTF_8);
		}

		boolean advance() throws IOException {
			text = in.readLine();
			if (text == null) {
				return false;
			}
			int first = text.indexOf('\t');
			int second = text.indexOf('\t', first + 1);
			start = Long.parseLong(text, 0, first, 10);
			index = Long.parseLong(text, first + 1, second, 10);
			return true;
		}

		@Override
		public void close() throws IOException {
			in.close();
		}
	}

	@FunctionalInterface
	private interface LineSink {
		void accept(Line line) throws IOException;
	}

	/** 정렬된 파일들을 (시작 시각, 순번) 순으로 합쳐 한 줄씩 넘긴다. 다 읽은 파일은 지운다. */
	private static void merge(List<Path> files, LineSink sink) throws IOException {
		PriorityQueue<Line> heap = new PriorityQueue<>(
				Comparator.<Line>comparingLong(l -> l.start).thenComparingLong(l -> l.index));
		List<Line> opened = new ArrayList<>();
		try {
			for (Path file : files) {
				Line line = new Line(file);
				opened.add(line);
				if (line.advance()) {
					heap.add(line);
				}
			}
			for (Line line; (line = heap.poll()) != null;) {
				sink.accept(line);
				if (line.advance()) {
					heap.add(line);
				}
			}
		}
		finally {
			for (Line line : opened) {
				line.close();
			}
		}
		for (Path file : files) {
			Files.deleteIfExists(file);
		}
	}
}
