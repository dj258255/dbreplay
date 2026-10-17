package io.dbreplay.trace;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 트래픽 파일을 한 줄씩 읽는다. 파일 전체를 메모리에 올리지 않는다. */
public final class TraceReader implements Closeable {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final BufferedReader in;
	private final TraceHeader header;
	private final Predicate<TraceRecord> keep;

	public TraceReader(Reader in) throws IOException {
		this(in, r -> true);
	}

	/** @param keep 이것이 참인 레코드만 돌려준다. 재생을 나눠 틀거나 쓰기만 틀 때 쓴다(#81) */
	public TraceReader(Reader in, Predicate<TraceRecord> keep) throws IOException {
		this.keep = keep;
		this.in = new BufferedReader(in);
		String first = this.in.readLine();
		if (first == null) {
			throw new IOException("트래픽 파일이 비어 있다");
		}
		this.header = MAPPER.readValue(first, TraceHeader.class);
		if (!TraceHeader.FORMAT.equals(header.format()) || header.version() != TraceHeader.VERSION) {
			throw new IOException("읽을 수 없는 트래픽 파일이다: format=" + header.format() + ", version=" + header.version());
		}
	}

	public TraceHeader header() {
		return header;
	}

	/** 다음 레코드. 끝이면 null. */
	public TraceRecord next() throws IOException {
		for (String line; (line = in.readLine()) != null;) {
			TraceRecord r = MAPPER.readValue(line, TraceRecord.class);
			if (keep.test(r)) {
				return r;
			}
		}
		return null;
	}

	@Override
	public void close() throws IOException {
		in.close();
	}
}
