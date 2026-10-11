package io.dbreplay.postgres;

import java.util.HashMap;
import java.util.Map;

/**
 * 확장 프로토콜로 실행된 문장의 $1, $2 자리에 로그의 인자 값을 끼워 넣는다.
 *
 * <p>로그는 본문과 인자를 따로 적는다. 본문은 {@code UPDATE t SET a = $1 WHERE id = $2}이고 인자는 DETAIL에
 * {@code parameters: $1 = '5', $2 = '42'}로 온다. 값은 따옴표째 그대로 넣는다. PostgreSQL은 따옴표로 싼
 * 리터럴의 자료형을 문맥에서 정하므로 숫자 자리에 {@code '5'}가 들어가도 된다.
 */
final class PgParameters {

	private static final String PREFIX = "parameters: ";

	private PgParameters() {
	}

	static String inline(String sql, String detail) {
		if (detail == null || !detail.startsWith(PREFIX)) {
			return sql;
		}
		Map<Integer, String> values = parse(detail.substring(PREFIX.length()));
		StringBuilder out = new StringBuilder(sql.length() + 32);
		int i = 0;
		int n = sql.length();
		while (i < n) {
			char c = sql.charAt(i);
			if (c == '\'' || c == '"') {
				int end = closingQuote(sql, i, c);
				out.append(sql, i, end);
				i = end;
			}
			else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
				int end = sql.indexOf('\n', i);
				end = end < 0 ? n : end;
				out.append(sql, i, end);
				i = end;
			}
			else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
				int end = sql.indexOf("*/", i + 2);
				end = end < 0 ? n : end + 2;
				out.append(sql, i, end);
				i = end;
			}
			else if (c == '$' && i + 1 < n && Character.isDigit(sql.charAt(i + 1))) {
				int end = i + 1;
				while (end < n && Character.isDigit(sql.charAt(end))) {
					end++;
				}
				String value = values.get(Integer.parseInt(sql.substring(i + 1, end)));
				out.append(value != null ? value : sql.substring(i, end));
				i = end;
			}
			else if (c == '$') {
				// $tag$ ... $tag$ 문자열. 안에 $1처럼 보이는 글자가 있어도 인자가 아니다
				int tagEnd = sql.indexOf('$', i + 1);
				String tag = tagEnd < 0 ? null : sql.substring(i, tagEnd + 1);
				int close = tag == null || !tag.matches("\\$[A-Za-z_]\\w*\\$|\\$\\$") ? -1 : sql.indexOf(tag, tagEnd + 1);
				int end = close < 0 ? i + 1 : close + tag.length();
				out.append(sql, i, end);
				i = end;
			}
			else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}

	/** {@code $1 = 'a', $2 = NULL, $3 = 'it''s'}를 번호별 리터럴로 나눈다. */
	private static Map<Integer, String> parse(String list) {
		Map<Integer, String> values = new HashMap<>();
		int i = 0;
		int n = list.length();
		while (i < n) {
			int dollar = list.indexOf('$', i);
			int equals = dollar < 0 ? -1 : list.indexOf(" = ", dollar);
			if (equals < 0) {
				break;
			}
			int number = Integer.parseInt(list.substring(dollar + 1, equals));
			int start = equals + 3;
			int end;
			if (start < n && list.charAt(start) == '\'') {
				end = closingQuote(list, start, '\'');
			}
			else {
				end = list.indexOf(',', start);
				end = end < 0 ? n : end;
			}
			values.put(number, list.substring(start, end));
			i = end;
		}
		return values;
	}

	/** open 위치의 따옴표가 닫힌 바로 다음 위치. 겹따옴표는 따옴표 한 글자로 본다. */
	private static int closingQuote(String s, int open, char quote) {
		int i = open + 1;
		while (i < s.length()) {
			if (s.charAt(i) == quote) {
				if (i + 1 < s.length() && s.charAt(i + 1) == quote) {
					i += 2;
					continue;
				}
				return i + 1;
			}
			i++;
		}
		return s.length();
	}
}
