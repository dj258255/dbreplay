package io.dbreplay.replay;

/** 문장 결과를 받는 곳. 여러 스레드가 동시에 부른다. */
@FunctionalInterface
public interface ResultSink {

	void accept(ReplayResult result);
}
