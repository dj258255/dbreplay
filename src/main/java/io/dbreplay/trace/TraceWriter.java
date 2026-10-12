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

	/** 레코드를 파일의 한 줄이 될 JSON으로 바꾼다. 줄바꿈은 붙이지 않는다. */
	public static String serialize(TraceRecord record) throws IOException {
		return MAPPER.writeValueAsString(record);
	}

	/** {@link #serialize}로 만들어 둔 줄을 그대로 쓴다. 큰 로그를 병합할 때 다시 읽고 만드는 일을 줄인다. */
	public void writeSerialized(String json) throws IOException {
		out.write(json);
		out.write('\n');
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
