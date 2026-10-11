package io.dbreplay.trace;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 트래픽 파일을 JSON Lines로 쓴다. 첫 줄은 머리말이고 그 뒤는 한 줄에 레코드 하나다. */
public final class TraceWriter implements Closeable {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final Writer out;

	public TraceWriter(Writer out, TraceHeader header) throws IOException {
		this.out = out;
		writeLine(header);
	}

	public void write(TraceRecord record) throws IOException {
		writeLine(record);
	}

	private void writeLine(Object value) throws IOException {
		out.write(MAPPER.writeValueAsString(value));
		out.write('\n');
	}

	@Override
	public void close() throws IOException {
		out.close();
	}
}
