package io.dbreplay.report;

import java.util.regex.Pattern;

/**
 * 값만 다른 문장을 한 모양으로 묶는다. 글자와 숫자 리터럴을 ?로 바꾸고 공백을 정리한다.
 *
 * <p>SQL을 파싱하지 않는다. 큰따옴표 안은 건드리지 않는다. PostgreSQL에서는 식별자이고, MySQL에서
 * 문자열로 쓴 경우에는 값이 모양에 남는다.
 */
public final class SqlShape {

	private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
	private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
	private static final Pattern STRING = Pattern.compile("'(?:[^'\\\\]|\\\\.|'')*'", Pattern.DOTALL);
	private static final Pattern NUMBER = Pattern.compile("(?<![\\w$.])-?(?:0x[0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)(?![\\w.])");
	private static final Pattern SPACE = Pattern.compile("\\s+");
	private static final Pattern LIST = Pattern.compile("\\(\\s*\\?(?:\\s*,\\s*\\?)+\\s*\\)");
	private static final Pattern TUPLES = Pattern.compile("(\\(\\?\\+?\\))(?:\\s*,\\s*\\(\\?\\+?\\))+");

	private SqlShape() {
	}

	public static String of(String sql) {
		String s = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
		s = LINE_COMMENT.matcher(s).replaceAll(" ");
		s = STRING.matcher(s).replaceAll("?");
		s = NUMBER.matcher(s).replaceAll("?");
		s = SPACE.matcher(s).replaceAll(" ").strip();
		s = LIST.matcher(s).replaceAll("(?+)"); // IN (?, ?, ?)와 VALUES (?, ?)를 값의 개수와 무관하게
		s = TUPLES.matcher(s).replaceAll("$1, ..."); // 여러 행을 넣는 INSERT를 행 수와 무관하게
		return s;
	}
}
