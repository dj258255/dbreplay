package io.dbreplay.convert;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** SQL을 파싱하지 않고 앞 단어만 본다. 문장 종류를 가르는 데는 이것으로 충분하다. */
public final class SqlWords {

	private static final Pattern LEADING_NOISE = Pattern.compile("\\A(?:\\s+|/\\*.*?\\*/|--[^\\n]*\\n?|#[^\\n]*\\n?)+", Pattern.DOTALL);
	private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
	private static final Set<String> WRITE_HEADS = Set.of("INSERT", "UPDATE", "DELETE", "REPLACE", "LOAD", "CALL", "MERGE", "COPY");

	private SqlWords() {
	}

	/** 주석을 걷어 낸 앞 단어들. 대문자로, 많아야 네 조각을 돌려준다. 네 번째 조각에는 나머지가 다 들어 있다. */
	public static String[] head(String sql) {
		return COMMENT.matcher(LEADING_NOISE.matcher(sql).replaceFirst("")).replaceAll(" ")
				.strip().toUpperCase(Locale.ROOT).split("\\s+", 4);
	}

	public static String first(String sql) {
		return head(sql)[0];
	}

	/** 데이터를 바꿀 수 있는 문장인가. CALL은 안을 볼 수 없어 바꾸는 쪽으로 친다. */
	public static boolean writes(String sql) {
		return WRITE_HEADS.contains(first(sql));
	}

	/** COMMIT AND CHAIN처럼 끝내면서 곧바로 새 트랜잭션을 여는가. */
	public static boolean chained(String[] head) {
		String all = String.join(" ", head);
		return all.contains(" AND CHAIN") && !all.contains(" AND NO CHAIN");
	}
}
