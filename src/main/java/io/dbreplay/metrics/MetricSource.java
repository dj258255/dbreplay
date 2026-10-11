package io.dbreplay.metrics;

import java.util.Map;

/** 지금의 지표 값을 읽어 오는 곳. 표본기가 일정 간격으로 부른다. */
public interface MetricSource extends AutoCloseable {

	/** @return 지표 이름과 값. 이번에 읽지 못한 지표는 빼고 돌려준다 */
	Map<String, Double> read() throws Exception;

	@Override
	default void close() {
	}
}
