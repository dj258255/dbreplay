package io.dbreplay.mysql;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;

/**
 * {@code COM_STMT_EXECUTE}의 바이너리 인자를 SQL 리터럴로 바꾸고 준비한 문장의 {@code ?}에 끼워 넣는다(#79).
 * 재생은 문장을 통째로 보낸다. 슬로우 로그도 실행된 문장을 인자를 끼운 채로 적는다.
 */
final class BinaryValues {

	private BinaryValues() {
	}

	/** 형식 번호(낮은 바이트)와 부호 없음 표시(0x8000)를 보고 값 하나를 읽는다. */
	static String read(ByteBuffer b, int type) {
		b.order(ByteOrder.LITTLE_ENDIAN);
		boolean unsigned = (type & 0x8000) != 0;
		return switch (type & 0xff) {
			case 0x01 -> Long.toString(unsigned ? b.get() & 0xff : b.get());
			case 0x02, 0x0d -> Long.toString(unsigned ? b.getShort() & 0xffff : b.getShort());
			case 0x03, 0x09 -> Long.toString(unsigned ? b.getInt() & 0xffff_ffffL : b.getInt());
			case 0x08 -> unsigned ? Long.toUnsignedString(b.getLong()) : Long.toString(b.getLong());
			case 0x04 -> Float.toString(b.getFloat());
			case 0x05 -> Double.toString(b.getDouble());
			case 0x06 -> "NULL";
			case 0x0a, 0x07, 0x0c -> dateTime(b);
			case 0x0b -> time(b);
			default -> string(b); // 글자, 십진수, BLOB, JSON, BIT, ENUM, SET
		};
	}

	private static String string(ByteBuffer b) {
		byte[] bytes = new byte[(int) MysqlCapture.lenenc(b)];
		b.get(bytes);
		String text = MysqlCapture.utf8(bytes);
		if (text == null) {
			return "X'" + HexFormat.of().formatHex(bytes) + "'";
		}
		return "'" + text.replace("\\", "\\\\").replace("'", "''") + "'";
	}

	private static String dateTime(ByteBuffer b) {
		int length = b.get() & 0xff;
		int year = 0, month = 0, day = 0, hour = 0, minute = 0, second = 0, micros = 0;
		if (length >= 4) {
			year = b.getShort() & 0xffff;
			month = b.get();
			day = b.get();
		}
		if (length >= 7) {
			hour = b.get();
			minute = b.get();
			second = b.get();
		}
		if (length >= 11) {
			micros = b.getInt();
		}
		String date = String.format("%04d-%02d-%02d", year, month, day);
		if (length <= 4) {
			return "'" + date + "'";
		}
		return "'" + date + String.format(" %02d:%02d:%02d", hour, minute, second) + (micros > 0 ? String.format(".%06d", micros) : "") + "'";
	}

	private static String time(ByteBuffer b) {
		int length = b.get() & 0xff;
		if (length == 0) {
			return "'00:00:00'";
		}
		boolean negative = b.get() != 0;
		long days = b.getInt() & 0xffff_ffffL;
		int hour = b.get(), minute = b.get(), second = b.get();
		int micros = length >= 12 ? b.getInt() : 0;
		return "'" + (negative ? "-" : "") + String.format("%02d:%02d:%02d", days * 24 + hour, minute, second)
				+ (micros > 0 ? String.format(".%06d", micros) : "") + "'";
	}

	/**
	 * 따옴표 안과 주석 안이 아닌 {@code ?}를 차례로 값으로 바꾼다. 값이 모자라면 남은 {@code ?}는 그대로 둔다.
	 */
	static String inline(String sql, String[] values) {
		StringBuilder out = new StringBuilder(sql.length() + 16 * values.length);
		int next = 0;
		for (int i = 0; i < sql.length(); i++) {
			char c = sql.charAt(i);
			if (c == '\'' || c == '"' || c == '`') {
				int end = closing(sql, i, c);
				out.append(sql, i, end);
				i = end - 1;
			}
			else if (c == '-' && sql.startsWith("-- ", i) || c == '#') {
				int end = sql.indexOf('\n', i);
				end = end < 0 ? sql.length() : end;
				out.append(sql, i, end);
				i = end - 1;
			}
			else if (c == '/' && sql.startsWith("/*", i)) {
				int end = sql.indexOf("*/", i + 2);
				end = end < 0 ? sql.length() : end + 2;
				out.append(sql, i, end);
				i = end - 1;
			}
			else if (c == '?' && next < values.length) {
				out.append(values[next++]);
			}
			else {
				out.append(c);
			}
		}
		return out.toString();
	}

	/** 여는 따옴표 다음부터 닫는 따옴표 다음 자리. 두 번 겹친 따옴표와 역슬래시 뒤의 글자는 닫는 것으로 보지 않는다. */
	private static int closing(String sql, int open, char quote) {
		for (int i = open + 1; i < sql.length(); i++) {
			char c = sql.charAt(i);
			if (c == '\\' && quote != '`') {
				i++;
			}
			else if (c == quote) {
				if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
					i++;
				}
				else {
					return i + 1;
				}
			}
		}
		return sql.length();
	}
}
