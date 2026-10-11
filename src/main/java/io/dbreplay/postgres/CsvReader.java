package io.dbreplay.postgres;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL csvlog가 쓰는 CSV를 한 행씩 읽는다. 따옴표 안의 줄바꿈과 겹따옴표("")를 다룬다.
 * 따옴표 없이 비어 있는 칸은 NULL이라 null로 돌려준다.
 */
final class CsvReader {

	private final BufferedReader in;

	CsvReader(Reader in) {
		this.in = in instanceof BufferedReader b ? b : new BufferedReader(in);
	}

	/** 다음 행. 끝이면 null. */
	List<String> next() throws IOException {
		int c = in.read();
		if (c < 0) {
			return null;
		}
		List<String> fields = new ArrayList<>(26);
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		boolean wasQuoted = false;
		while (true) {
			if (quoted) {
				if (c < 0) {
					break; // 파일이 행 중간에서 끝났다
				}
				if (c == '"') {
					int next = in.read();
					if (next == '"') {
						field.append('"');
					}
					else {
						quoted = false;
						c = next;
						continue;
					}
				}
				else {
					field.append((char) c);
				}
			}
			else if (c == '"') {
				quoted = true;
				wasQuoted = true;
			}
			else if (c == ',' || c == '\n' || c < 0) {
				fields.add(wasQuoted || !field.isEmpty() ? field.toString() : null);
				field.setLength(0);
				wasQuoted = false;
				if (c != ',') {
					break;
				}
			}
			else if (c != '\r') {
				field.append((char) c);
			}
			c = in.read();
		}
		return fields;
	}
}
