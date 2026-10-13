package io.dbreplay.trace;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * 소유자만 읽고 쓸 수 있는(rw-------) 파일을 만들어 쓰기용으로 연다.
 *
 * <p>스냅샷 덤프에는 운영 DB의 데이터 전체가, 트래픽 파일에는 값이 그대로 든 SQL이 들어 있다. 기본 권한
 * (보통 rw-r--r--)으로 만들면 같은 장비의 다른 사용자가 읽을 수 있다.
 *
 * <p>권한은 만드는 순간에 줘야 하고, 만들 때 연 핸들에 그대로 써야 한다. 파일을 만들고 닫은 뒤 경로로 다시
 * 열면, 그 사이에 그 폴더에 쓸 수 있는 다른 사용자가 경로를 심볼릭 링크로 바꿔 덤프와 SQL을 다른 파일로
 * 보낼 수 있다. {@link #open}이 돌려준 스트림에 바로 쓰면 그 틈이 없다.
 */
public final class PrivateFile {

	private static final FileAttribute<?> OWNER_ONLY =
			PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

	private PrivateFile() {
	}

	/**
	 * 이미 있으면 지우고 새로 만들어 쓰기용으로 연다. 돌려준 스트림에 그대로 쓴다.
	 *
	 * <p>{@code CREATE_NEW}로 열어서, 지운 뒤 만들기 전에 누가 그 경로에 무엇을 만들어 두면(심볼릭 링크 포함)
	 * 예외로 끝난다. 그것이 맞는 동작이다. 따라 쓰지 않는다. POSIX 권한을 쓰는 파일 시스템이 아니면 권한은
	 * 기본값으로 둔다.
	 */
	public static OutputStream open(Path file) throws IOException {
		Files.deleteIfExists(file);
		Set<StandardOpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			return Channels.newOutputStream(Files.newByteChannel(file, options, OWNER_ONLY));
		}
		return Channels.newOutputStream(Files.newByteChannel(file, options));
	}
}