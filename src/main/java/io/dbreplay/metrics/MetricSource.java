package io.dbreplay.metrics;

import java.util.List;
import java.util.Map;

/** 지금의 지표 값을 읽어 오는 곳. 표본기가 일정 간격으로 부른다. */
public interface MetricSource extends AutoCloseable {

	/** @return 지표 이름과 값. 이번에 읽지 못한 지표는 빼고 돌려준다 */
	Map<String, Double> read() throws Exception;

	/**
	 * 값을 내다가 도중에 읽지 못하게 된 일을 사람이 읽을 말로 돌려준다. 읽는 중에 멈춘 것이 없으면 빈 목록이다.
	 * 표본기가 멈춘 뒤에 부른다.
	 */
	default List<String> failures() {
		return List.of();
	}

	@Override
	default void close() {
	}
}
