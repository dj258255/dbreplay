package io.dbreplay.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateFileTest {

	private static final Set<PosixFilePermission> OWNER_ONLY =
			Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	/** POSIX 권한이 없는 파일 시스템에서는 권한 확인을 건너뛴다. */
	private static void requirePosix() {
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
				"POSIX 권한이 없는 파일 시스템에서는 권한을 확인하지 않는다");
	}

	/** open이 돌려준 스트림에 그대로 쓴 내용이 파일에 있고, 파일은 소유자만 읽고 쓸 수 있다. */
	@Test
	void writesThroughTheOpenedStreamToAPrivateFile(@TempDir Path dir) throws IOException {
		requirePosix();
		Path file = dir.resolve("dump.sql");

		try (OutputStream out = PrivateFile.open(file)) {
			out.write("BEGIN\n".getBytes(StandardCharsets.UTF_8));
		}

		assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(file));
		assertEquals("BEGIN\n", Files.readString(file));
	}

	/** 다른 사용자가 읽을 수 있던 파일이 이미 있어도, open 뒤에는 새 내용으로 바뀌고 소유자만 읽을 수 있다. */
	@Test
	void replacesAnExistingWorldReadableFile(@TempDir Path dir) throws IOException {
		requirePosix();
		Path file = dir.resolve("trace.jsonl");
		Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--")));
		Files.writeString(file, "예전 내용");

		try (OutputStream out = PrivateFile.open(file)) {
			out.write("새 내용".getBytes(StandardCharsets.UTF_8));
		}

		assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(file));
		assertEquals("새 내용", Files.readString(file));
	}

	/**
	 * 경로가 다른 파일을 가리키는 심볼릭 링크여도, open은 그 링크를 지우고 새 파일을 만들어 거기에 쓴다.
	 * 링크가 가리키던 파일의 내용은 그대로다.
	 */
	@Test
	void writesToTheNewFileNotToWhateverASymlinkPointedAt(@TempDir Path dir) throws IOException {
		requirePosix();
		Path target = dir.resolve("real.txt");
		Files.writeString(target, "원본");
		Path link = dir.resolve("link.sql");
		try {
			Files.createSymbolicLink(link, target);
		}
		catch (UnsupportedOperationException | IOException e) {
			assumeTrue(false, "심볼릭 링크를 만들 수 없는 환경이다");
		}

		try (OutputStream out = PrivateFile.open(link)) {
			out.write("새 내용".getBytes(StandardCharsets.UTF_8));
		}

		assertEquals("원본", Files.readString(target), "링크가 가리키던 파일은 그대로다");
		assertEquals("새 내용", Files.readString(link));
	}
}