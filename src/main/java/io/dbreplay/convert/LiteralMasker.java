package io.dbreplay.convert;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.SplittableRandom;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SQL 안의 리터럴 값을 가린다. 녹화 파일을 운영 밖으로 가져가기 전에 쓴다.
 *
 * <p>SQL을 파싱하지 않고 리터럴만 찾아 바꾼다. 길이와 글자의 종류(숫자, 영문 대문자와 소문자, 한글)를 지키고,
 * 같은 값은 언제나 같은 값으로 바꾼다. 넣은 값을 뒤에서 다시 찾는 문장이 가린 뒤에도 같은 행을 찾게 하려는 것이다.
 *
 * <p>값의 종류는 따옴표가 아니라 내용으로 나눈다. PostgreSQL의 로그는 인자를 전부 따옴표로 감싸 넣어서
 * ({@code aid = '152661'}) 따옴표로 나누면 기본키까지 문자열이 된다.
 *
 * <p>가리지 않는 것: 날짜와 시각 모양의 값(바꾸면 없는 날짜가 된다), 주석, 식별자, 16진수, LIMIT과 OFFSET의 수.
 * 어떤 열이 개인정보인지는 알지 못한다. 생년월일처럼 날짜로 된 개인정보는 남는다.
 */
public final class LiteralMasker {

	public enum Level {
		/** 글자로 된 값과 긴 숫자를 가린다. 짧은 숫자는 식별자로 보고 둔다 */
		STRINGS,
		/**
		 * 숫자까지 전부 가린다. 다른 행을 건드리게 되고, 값들 사이의 관계가 깨진다. 실험 13에서 100행짜리 범위 조회
		 * (BETWEEN 50100 AND 50199)의 두 끝이 따로 바뀌어 수만 행짜리가 됐고 재생이 끝나지 않았다
		 */
		ALL
	}

	/** 이 자릿수부터 '긴 숫자'로 본다. 전화번호, 카드번호, 주민등록번호가 여기에 든다 */
	public static final int LONG_NUMBER_DIGITS = 10;

	private static final Pattern NUMERIC = Pattern.compile("[+-]?\\d+(?:\\.\\d+)?");
	private static final Pattern DATE_OR_TIME = Pattern.compile(
			"\\d{4}-\\d{2}-\\d{2}(?:[ T]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?)?(?: ?(?:Z|UTC|[+-]\\d{2}(?::?\\d{2})?))?"
					+ "|\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?");

	private final Level level;
	private final boolean mysql;
	private final Mac mac;

	/**
	 * @param mysql MySQL의 문장이면 true. 큰따옴표 안을 문자열로 보고 역슬래시를 이스케이프로 읽는다.
	 *              PostgreSQL에서는 큰따옴표 안이 식별자이고 역슬래시는 E'' 문자열에서만 이스케이프다
	 * @param key   같은 값을 같은 값으로 바꾸는 기준. 키가 다르면 결과가 다르다
	 */
	public LiteralMasker(Level level, boolean mysql, byte[] key) {
		this.level = level;
		this.mysql = mysql;
		try {
			this.mac = Mac.getInstance("HmacSHA256");
			this.mac.init(new SecretKeySpec(key, "HmacSHA256"));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 변환 한 번에만 쓰고 버리는 키. 남기지 않으므로 가린 값에서 원래 값을 찾을 기준이 없다. */
	public static byte[] randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	public String mask(String sql) {
		StringBuilder out = new StringBuilder(sql.length());
		int n = sql.length();
		int i = 0;
		while (i < n) {
			char c = sql.charAt(i);
			if (c == '\'' || (c == '"' && mysql)) {
				boolean backslash = mysql || (i > 0 && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e'));
				int end = endOfQuoted(sql, i, c, backslash);
				boolean closed = end <= n;
				out.append(c).append(maskQuoted(sql.substring(i + 1, closed ? end - 1 : n), backslash));
				if (closed) {
					out.append(c);
				}
				i = end;
			}
			else if (c == '"' || c == '`') {
				int end = endOfQuoted(sql, i, c, false);
				out.append(sql, i, end);
				i = end;
			}
			else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
				int close = sql.indexOf("*/", i + 2);
				int end = close < 0 ? n : close + 2;
				out.append(sql, i, end);
				i = end;
			}
			else if (startsLineComment(sql, i)) {
				int newline = sql.indexOf('\n', i);
				int end = newline < 0 ? n : newline;
				out.append(sql, i, end);
				i = end;
			}
			else if (isDigit(c) && (i == 0 || !isWordChar(sql.charAt(i - 1)))) {
				int end = endOfNumber(sql, i);
				String number = sql.substring(i, end);
				boolean plain = end == n || !isWordChar(sql.charAt(end));
				out.append(plain && !number.startsWith("0x") && !afterLimit(out) ? maskNumber(number) : number);
				i = end;
			}
			else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}

	/** MySQL에서 --는 뒤에 공백이 와야 주석이다. {@code a--1}은 뺄셈이다. */
	private boolean startsLineComment(String sql, int i) {
		char c = sql.charAt(i);
		if (c == '#') {
			return mysql;
		}
		if (c != '-' || i + 1 >= sql.length() || sql.charAt(i + 1) != '-') {
			return false;
		}
		return !mysql || i + 2 >= sql.length() || Character.isWhitespace(sql.charAt(i + 2));
	}

	/** 여는 따옴표의 위치를 받아 닫는 따옴표 다음 위치를 돌려준다. 닫히지 않았으면 문장의 길이보다 큰 값이다. */
	private static int endOfQuoted(String sql, int open, char quote, boolean backslash) {
		int n = sql.length();
		int i = open + 1;
		while (i < n) {
			char c = sql.charAt(i);
			if (c == '\\' && backslash) {
				i += 2;
			}
			else if (c == quote) {
				if (i + 1 < n && sql.charAt(i + 1) == quote) {
					i += 2; // 따옴표를 두 번 써서 따옴표 하나를 나타낸 것
				}
				else {
					return i + 1;
				}
			}
			else {
				i++;
			}
		}
		return n + 1;
	}

	private static int endOfNumber(String sql, int start) {
		int n = sql.length();
		int i = start;
		if (sql.startsWith("0x", start) || sql.startsWith("0X", start)) {
			i += 2;
			while (i < n && Character.digit(sql.charAt(i), 16) >= 0) {
				i++;
			}
			return i;
		}
		while (i < n && isDigit(sql.charAt(i))) {
			i++;
		}
		if (i + 1 < n && sql.charAt(i) == '.' && isDigit(sql.charAt(i + 1))) {
			i++;
			while (i < n && isDigit(sql.charAt(i))) {
				i++;
			}
		}
		return i;
	}

	private String maskQuoted(String content, boolean backslash) {
		if (content.isEmpty() || DATE_OR_TIME.matcher(content).matches()) {
			return content;
		}
		if (NUMERIC.matcher(content).matches()) {
			return maskNumber(content);
		}
		SplittableRandom random = randomFor(content);
		StringBuilder out = new StringBuilder(content.length());
		for (int i = 0; i < content.length(); i++) {
			char c = content.charAt(i);
			if (c == '\\' && backslash && i + 1 < content.length()) {
				out.append(c).append(content.charAt(++i)); // \n 같은 이스케이프는 그대로 둔다
			}
			else {
				out.append(replace(c, random));
			}
		}
		return out.toString();
	}

	/** 부호와 소수점, 자릿수를 지킨다. 맨 앞자리는 0이 아닌 숫자로 바꿔 크기의 자릿수가 같게 한다. */
	private String maskNumber(String number) {
		long digits = number.chars().filter(LiteralMasker::isDigit).count();
		if (level == Level.STRINGS && digits < LONG_NUMBER_DIGITS) {
			return number;
		}
		SplittableRandom random = randomFor(number);
		StringBuilder out = new StringBuilder(number.length());
		boolean first = true;
		for (int i = 0; i < number.length(); i++) {
			char c = number.charAt(i);
			if (!isDigit(c)) {
				out.append(c);
			}
			else if (first && c != '0') {
				out.append((char) ('1' + random.nextInt(9)));
				first = false;
			}
			else {
				out.append(first ? c : (char) ('0' + random.nextInt(10)));
				first = false;
			}
		}
		return out.toString();
	}

	private static char replace(char c, SplittableRandom random) {
		if (isDigit(c)) {
			return (char) ('0' + random.nextInt(10));
		}
		if (c >= 'a' && c <= 'z') {
			return (char) ('a' + random.nextInt(26));
		}
		if (c >= 'A' && c <= 'Z') {
			return (char) ('A' + random.nextInt(26));
		}
		if (c >= '가' && c <= '힣') {
			return (char) ('가' + random.nextInt('힣' - '가' + 1));
		}
		return c;
	}

	/** 값마다 정해지는 난수. 같은 값은 같은 순서의 난수를 받는다. */
	private SplittableRandom randomFor(String value) {
		byte[] hash = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
		long seed = 0;
		for (int i = 0; i < 8; i++) {
			seed = (seed << 8) | (hash[i] & 0xff);
		}
		return new SplittableRandom(seed);
	}

	/** 바로 앞 단어가 LIMIT이나 OFFSET인가. 그 수를 바꾸면 문장이 돌려주는 행 수가 달라진다. */
	private static boolean afterLimit(StringBuilder out) {
		int end = out.length();
		while (end > 0 && (Character.isWhitespace(out.charAt(end - 1)) || out.charAt(end - 1) == ',' || isDigit(out.charAt(end - 1)))) {
			end--;
		}
		int start = end;
		while (start > 0 && Character.isLetter(out.charAt(start - 1))) {
			start--;
		}
		String word = out.substring(start, end);
		return word.equalsIgnoreCase("LIMIT") || word.equalsIgnoreCase("OFFSET");
	}

	private static boolean isDigit(int c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isWordChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.';
	}
}
