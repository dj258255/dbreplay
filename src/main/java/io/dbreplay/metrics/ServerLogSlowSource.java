package io.dbreplay.metrics;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL 서버 로그의 끝을 따라 읽어, 기준 시간을 넘겨 끝난 문장의 누적 수({@code slow_finished_total})를 낸다(#82).
 * PostgreSQL에는 MySQL의 실행 시간 분포 같은 것이 기본으로 없다. 서버가 {@code log_min_duration_statement}로 남기는
 * {@code duration: N ms} 줄을 센다. 그 설정이 기준 시간보다 크면 기준과 그 사이의 문장을 놓친다.
 *
 * <p>로그 파일을 이 도구가 읽을 수 있는 자리에 있어야 한다. 시작할 때의 파일 끝부터 센다. 파일이 줄어들면(로그를 돌렸다)
 * 처음부터 다시 읽는다. 줄 중간에서 끊긴 끝은 다음 표본으로 넘긴다.
 */
public final class ServerLogSlowSource implements MetricSource {

	private static final Pattern DURATION = Pattern.compile("duration: ([0-9]+(?:\\.[0-9]+)?) ms");

	private final Path file;
	private final BigDecimal slowMillis;
	private long position;
	private byte[] carry = new byte[0];
	private long total;

	public ServerLogSlowSource(Path file, long slowMillis) throws IOException {
		this.file = file;
		this.slowMillis = BigDecimal.valueOf(slowMillis);
		this.position = Files.size(file);
	}

	@Override
	public Map<String, Double> read() throws IOException {
		long size = Files.size(file);
		if (size < position) {
			position = 0;
			carry = new byte[0];
		}
		if (size > position) {
			byte[] fresh;
			try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
				in.seek(position);
				fresh = new byte[(int) Math.min(size - position, Integer.MAX_VALUE - 8)];
				in.readFully(fresh);
			}
			position += fresh.length;
			byte[] chunk = Arrays.copyOf(carry, carry.length + fresh.length);
			System.arraycopy(fresh, 0, chunk, carry.length, fresh.length);
			int lastNewline = lastIndexOf(chunk, (byte) '\n');
			// 바이트에서 줄 끝을 찾아 그 앞만 글자로 바꾼다. 여러 바이트 글자가 조각 경계에서 잘리지 않게 하려는 것이다
			total += count(new String(chunk, 0, lastNewline + 1, StandardCharsets.UTF_8));
			carry = Arrays.copyOfRange(chunk, lastNewline + 1, chunk.length);
		}
		return Map.of(DbMetricSource.SLOW_FINISHED_TOTAL, (double) total);
	}

	/** 기준 시간을 넘긴 duration 줄의 수. 같은 기준이면 MySQL의 것처럼 기준 시간보다 길게 걸린 것만 센다. */
	long count(String text) {
		long n = 0;
		Matcher m = DURATION.matcher(text);
		while (m.find()) {
			if (new BigDecimal(m.group(1)).compareTo(slowMillis) > 0) {
				n++;
			}
		}
		return n;
	}

	private static int lastIndexOf(byte[] bytes, byte b) {
		for (int i = bytes.length - 1; i >= 0; i--) {
			if (bytes[i] == b) {
				return i;
			}
		}
		return -1;
	}
}
