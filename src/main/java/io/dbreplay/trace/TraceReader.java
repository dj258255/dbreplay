package io.dbreplay.trace;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 트래픽 파일을 한 줄씩 읽는다. 파일 전체를 메모리에 올리지 않는다. */
public final class TraceReader implements Closeable {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final BufferedReader in;
	private final TraceHeader header;

	public TraceReader(Reader in) throws IOException {
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
		String line = in.readLine();
		return line == null ? null : MAPPER.readValue(line, TraceRecord.class);
	}

	@Override
	public void close() throws IOException {
		in.close();
	}
}
