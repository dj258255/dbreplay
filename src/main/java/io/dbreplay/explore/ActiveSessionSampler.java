package io.dbreplay.explore;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import io.dbreplay.replay.JdbcTarget;

/**
 * 재생하는 동안 대상에서 지금 문장을 실행 중인 연결 수를 일정 간격으로 뜬다.
 *
 * <p>MySQL은 {@code Threads_running}, PostgreSQL은 {@code pg_stat_activity}에서 active인 백엔드 수다.
 * 뜨는 데 쓰는 연결 자신은 뺀다.
 */
public final class ActiveSessionSampler implements AutoCloseable {

	private static final String MYSQL = "SELECT VARIABLE_VALUE - 1 FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Threads_running'";
	private static final String POSTGRES = "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND pid <> pg_backend_pid()";

	private final Connection connection;
	private final String query;
	private final long intervalMillis;
	private final List<Integer> samples = new ArrayList<>();
	private final Thread thread;
	private volatile boolean running = true;

	public ActiveSessionSampler(JdbcTarget target, long intervalMillis) throws SQLException {
		this.connection = target.connect(null);
		this.query = target.isPostgres() ? POSTGRES : MYSQL;
		this.intervalMillis = intervalMillis;
		this.thread = Thread.ofPlatform().name("dbreplay-sampler").daemon().start(this::loop);
	}

	private void loop() {
		try (Statement statement = connection.createStatement()) {
			while (running) {
				try (ResultSet rows = statement.executeQuery(query)) {
					if (rows.next()) {
						synchronized (samples) {
							samples.add(rows.getInt(1));
						}
					}
				}
				Thread.sleep(intervalMillis);
			}
		}
		catch (SQLException | InterruptedException e) {
			// 대상이 응답하지 않으면 여기서 멈춘다. 그때까지 뜬 값만 남는다
		}
	}

	/** 뜨기를 멈추고 지금까지 뜬 값을 시간 순서로 돌려준다. */
	public int[] stop() {
		running = false;
		thread.interrupt();
		try {
			thread.join(2_000);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		synchronized (samples) {
			return samples.stream().mapToInt(Integer::intValue).toArray();
		}
	}

	@Override
	public void close() {
		stop();
		try {
			connection.close();
		}
		catch (SQLException ignored) {
			// 닫는 중의 오류는 결과에 영향이 없다
		}
	}
}
