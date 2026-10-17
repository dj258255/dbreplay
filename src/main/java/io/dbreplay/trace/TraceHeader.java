package io.dbreplay.trace;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 트래픽 파일의 첫 줄. 뒤따르는 줄들이 어디서 왔는지 적는다.
 *
 * @param format  항상 {@link #FORMAT}
 * @param version 줄 형식의 판 번호. 호환이 깨질 때만 올린다
 * @param engine  원본 기종 (예: mysql)
 * @param source  녹화 입력의 종류 (예: slow-log)
 * @param mask    리터럴을 가렸으면 그 수준(strings, all). 가리지 않았으면 없다. 파일을 밖으로 내보내기 전에 여기를 본다
 * @param scope   변환할 때 걸러 낸 조건(예: database=shop;exclude-user=backup). 거르지 않았으면 없다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceHeader(String format, int version, String engine, String source, String mask, String scope) {

	public static final String FORMAT = "dbreplay-trace";
	public static final int VERSION = 1;

	public static TraceHeader of(String engine, String source) {
		return new TraceHeader(FORMAT, VERSION, engine, source, null, null);
	}

	public TraceHeader withMask(String mask) {
		return new TraceHeader(format, version, engine, source, mask, scope);
	}

	public TraceHeader withScope(String scope) {
		return new TraceHeader(format, version, engine, source, mask, scope);
	}
}
