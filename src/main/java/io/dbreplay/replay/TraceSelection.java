package io.dbreplay.replay;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import io.dbreplay.convert.SqlWords;
import io.dbreplay.trace.TraceReader;
import io.dbreplay.trace.TraceRecord;

/**
 * 트래픽의 일부만 틀 때 고르는 조건(#81). 글은 재생 작업마다 Kubernetes Job을 띄워 재생기를 늘리고, 읽기와 쓰기의 상한을 따로 낸다.
 */
public final class TraceSelection {

	private TraceSelection() {
	}

	/**
	 * 원본의 연결 번호로 나눈 {@code count}개 가운데 {@code index}번째(0부터). 한 연결의 문장은 같은 조각에 들어
	 * 연결 안의 순서와 트랜잭션이 깨지지 않는다.
	 */
	public static Predicate<TraceRecord> shard(int index, int count) {
		if (count < 1 || index < 0 || index >= count) {
			throw new IllegalArgumentException("조각은 0 이상 " + count + " 미만이어야 한다: " + index);
		}
		return r -> Math.floorMod(r.session().hashCode(), count) == index;
	}

	/** {@code i/N} 꼴. 사람이 쓰기 쉽게 1부터 센다. */
	public static Predicate<TraceRecord> shard(String text) {
		String[] parts = text.split("/");
		try {
			if (parts.length == 2) {
				return shard(Integer.parseInt(parts[0].strip()) - 1, Integer.parseInt(parts[1].strip()));
			}
		}
		catch (NumberFormatException e) {
			// 아래에서 같은 말로 알린다
		}
		throw new IllegalArgumentException("--shard는 1/4처럼 준다");
	}

	/**
	 * 쓰기가 하나라도 든 트랜잭션과, 트랜잭션 밖의 쓰기 문장만 고른다. 연결 종료 줄은 남긴다. 쓰기 트랜잭션 안의 읽기도 남긴다.
	 * 트랜잭션은 끝까지 봐야 쓰기가 드는지 알 수 있어서 트래픽 파일을 한 번 미리 읽는다.
	 */
	public static Predicate<TraceRecord> writeUnits(TraceReader trace) throws IOException {
		Set<String> writing = new HashSet<>();
		for (TraceRecord r; (r = trace.next()) != null;) {
			if (r.txn() != null && r.sql() != null && SqlWords.writes(r.sql())) {
				writing.add(key(r));
			}
		}
		return r -> r.endsSession() || (r.txn() == null ? r.sql() != null && SqlWords.writes(r.sql()) : writing.contains(key(r)));
	}

	private static String key(TraceRecord r) {
		return r.session() + "#" + r.txn();
	}
}
