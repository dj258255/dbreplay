package io.dbreplay.convert;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 변환할 때 재생 대상의 SQL만 남긴다. 글은 Flink로 재생 대상 클러스터의 SQL만 걸러 낸다(원문 대조 13번, #83).
 * 한 서버에 여러 서비스의 데이터베이스가 있으면 다른 서비스의 SQL이 섞이고, 그 부하까지 재생된다.
 *
 * <p>계정은 연결 단위로, 데이터베이스는 문장 단위로 본다. 계정은 연결 도중에 바뀌지 않고 기본 데이터베이스는 USE로 바뀐다.
 * 데이터베이스를 모르는 문장(MySQL 로그의 앞부분에 use 줄이 아직 없을 때)은 남기고 따로 센다. 버리면 대상의 문장이 빠질 수 있다.
 * 연결 종료 줄은 그 연결에 남긴 문장이 있을 때만 남긴다. 재생기가 빈 연결을 열고 닫지 않게 하려는 것이다.
 * 계정과 데이터베이스를 로그의 어디서 읽는지는 기종의 변환기가 안다.
 */
public final class ScopeFilter {

	private final Set<String> databases;
	private final Set<String> excludedUsers;
	private final Set<String> keptSessions = new HashSet<>();
	private long droppedStatements;
	private long droppedCloses;
	private long unknownDatabase;

	/** @param databases 남길 데이터베이스. 비었으면 데이터베이스로 거르지 않는다 */
	public ScopeFilter(Set<String> databases, Set<String> excludedUsers) {
		this.databases = Set.copyOf(databases);
		this.excludedUsers = Set.copyOf(excludedUsers);
	}

	/** 아무것도 거르지 않는다. */
	public static ScopeFilter all() {
		return new ScopeFilter(Set.of(), Set.of());
	}

	public boolean active() {
		return !databases.isEmpty() || !excludedUsers.isEmpty();
	}

	/** 문장 하나를 남길지. 남기면 그 연결의 종료 줄도 남긴다. */
	public boolean keeps(String session, String database, String user) {
		if (user != null && excludedUsers.contains(user)) {
			droppedStatements++;
			return false;
		}
		if (!databases.isEmpty()) {
			if (database == null) {
				unknownDatabase++;
			}
			else if (!databases.contains(database)) {
				droppedStatements++;
				return false;
			}
		}
		keptSessions.add(session);
		return true;
	}

	/** 연결 종료 줄을 남길지. 거르지 않을 때는 지금처럼 전부 남긴다. 부르면 그 연결을 잊는다. */
	public boolean keepsClose(String session) {
		boolean kept = keptSessions.remove(session) || !active();
		if (!kept) {
			droppedCloses++;
		}
		return kept;
	}

	public long droppedStatements() {
		return droppedStatements;
	}

	public long droppedCloses() {
		return droppedCloses;
	}

	/** 데이터베이스를 몰라 거르지 못하고 남긴 문장 수. */
	public long unknownDatabase() {
		return unknownDatabase;
	}

	/** 트래픽 파일의 머리말에 적을 조건. 거르지 않으면 null. 순서를 정해 같은 조건이 같은 글자가 되게 한다. */
	public String describe() {
		if (!active()) {
			return null;
		}
		StringBuilder s = new StringBuilder();
		if (!databases.isEmpty()) {
			s.append("database=").append(String.join(",", new TreeSet<>(databases)));
		}
		if (!excludedUsers.isEmpty()) {
			if (!s.isEmpty()) {
				s.append(';');
			}
			s.append("exclude-user=").append(String.join(",", new TreeSet<>(excludedUsers)));
		}
		return s.toString();
	}
}
