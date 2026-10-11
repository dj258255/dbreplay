package io.dbreplay.replay;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 문장 결과를 JSON Lines로 쓴다.
 *
 * <p>문장을 실행하는 스레드는 큐에 넣기만 하고, 직렬화와 파일 쓰기는 전용 스레드가 한다. 실행 스레드가
 * 파일 쓰기에 걸려 다음 문장이 늦게 나가는 일을 막으려는 것이다.
 */
public final class ResultWriter implements ResultSink, Closeable {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final ReplayResult END = new ReplayResult(-1, null, 0, 0, false, 0, 0, 0, null);

	private final BlockingQueue<ReplayResult> queue = new LinkedBlockingQueue<>();
	private final Writer out;
	private final Thread thread;
	private volatile IOException failure;

	public ResultWriter(Writer out) {
		this.out = out;
		this.thread = Thread.ofPlatform().name("dbreplay-results").start(this::drain);
	}

	@Override
	public void accept(ReplayResult result) {
		queue.add(result);
	}

	private void drain() {
		try {
			for (ReplayResult r; (r = queue.take()) != END;) {
				out.write(MAPPER.writeValueAsString(r));
				out.write('\n');
			}
		}
		catch (IOException e) {
			failure = e;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void close() throws IOException {
		queue.add(END);
		try {
			thread.join();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		out.close();
		if (failure != null) {
			throw new UncheckedIOException(failure);
		}
	}
}
