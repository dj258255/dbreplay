package io.dbreplay.trace;

/**
 * 트래픽 파일의 첫 줄. 뒤따르는 줄들이 어디서 왔는지 적는다.
 *
 * @param format  항상 {@link #FORMAT}
 * @param version 줄 형식의 판 번호. 호환이 깨질 때만 올린다
 * @param engine  원본 기종 (예: mysql)
 * @param source  녹화 입력의 종류 (예: slow-log)
 */
public record TraceHeader(String format, int version, String engine, String source) {

	public static final String FORMAT = "dbreplay-trace";
	public static final int VERSION = 1;

	public static TraceHeader of(String engine, String source) {
		return new TraceHeader(FORMAT, VERSION, engine, source);
	}
}
