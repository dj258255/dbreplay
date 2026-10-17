package io.dbreplay.postgres;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Queue;

import io.dbreplay.convert.PcapReader;
import io.dbreplay.convert.TcpStreams;

/**
 * 패킷 캡처에서 PostgreSQL 프론트엔드/백엔드 프로토콜(3.0)을 해석해 문장을 낸다(#79). 서버 로그를 켜지 않는다.
 *
 * <p>다루는 것: 시작 메시지(계정과 데이터베이스), 백엔드 키(연결 식별자로 쓴다), 단순 질의({@code Q}), 확장 질의({@code P}, {@code B},
 * {@code E}, {@code S}), 오류({@code E}), 끝({@code X}). 확장 질의의 인자는 csvlog의 {@code parameters:}와 같은 모양으로 만들어
 * {@link PgParameters}로 끼워 넣는다. 걸린 시간은 그 장비에서 본 요청부터 완료 응답까지다.
 *
 * <p>TLS로 암호화된 연결은 읽지 못한다. 바이너리 인자는 Parse에서 자료형을 밝힌 정수, 실수, 참거짓만 읽고 나머지는 bytea 꼴로 둔다.
 */
public final class PostgresCapture {

	/**
	 * 문장 하나나 연결 종료.
	 *
	 * @param startMicros 요청을 본 시각. 연결 종료면 닫힌 시각
	 * @param error       실패했으면 {@link CsvLogConverter#FAILED}, 아니면 0
	 */
	public record Entry(String session, long startMicros, long durationMicros, String sql, String database, String user, Integer error,
			boolean close) {
	}

	private static final int SSL_REQUEST = 80877103;
	private static final int GSS_REQUEST = 80877104;
	private static final int PROTOCOL_3 = 196608;
	/** 메시지 하나의 길이로 믿을 수 있는 상한. 이보다 길면 깨졌거나 이 도구가 다루지 않는 것(큰 COPY)으로 보고 그 연결을 버린다 */
	static final int MAX_MESSAGE = 64 * 1024 * 1024;

	private final PcapReader reader;
	private final TcpStreams streams;
	private boolean finished;
	private long lastMicros;
	private final Queue<Entry> ready = new ArrayDeque<>();
	private long unreadable;

	public PostgresCapture(InputStream pcap, int serverPort) throws IOException {
		this.reader = new PcapReader(pcap);
		this.streams = new TcpStreams(serverPort, (id, host, micros) -> new Connection(id));
	}

	public Entry next() throws IOException {
		while (ready.isEmpty()) {
			PcapReader.Segment s = reader.next();
			if (s == null) {
				if (!finished) {
					finished = true;
					streams.finish(lastMicros);
					continue;
				}
				return null;
			}
			lastMicros = s.micros();
			streams.accept(s);
		}
		return ready.poll();
	}

	public long skipped() {
		return reader.skipped();
	}

	public String losses() {
		return String.format("시작을 보지 못한 연결 %d, 조각을 놓친 연결 %d, 읽지 못한 연결(TLS, 모르는 메시지) %d (연결 %d개 가운데)",
				streams.unseenStart(), streams.lost(), unreadable, streams.opened());
	}

	private record Statement(String sql, int[] types) {
	}

	private static final class Pending {
		final boolean simple;
		final String sql;
		final long startMicros;
		boolean failed;

		Pending(boolean simple, String sql, long startMicros) {
			this.simple = simple;
			this.sql = sql;
			this.startMicros = startMicros;
		}
	}

	private final class Connection implements TcpStreams.Conversation {

		private final Bytes client = new Bytes();
		private final Bytes server = new Bytes();
		private String session;
		private String user;
		private String database;
		private boolean started;
		private boolean awaitingSslAnswer;
		private boolean dead;
		private boolean closed;
		private final Map<String, Statement> statements = new HashMap<>();
		private final Map<String, String> portals = new HashMap<>();
		private final Deque<Pending> pending = new ArrayDeque<>();
		private boolean skipUntilReady;

		Connection(String id) {
			this.session = "c" + id;
		}

		@Override
		public void data(boolean fromClient, byte[] bytes, long micros) {
			if (dead) {
				return;
			}
			Bytes in = fromClient ? client : server;
			in.add(bytes);
			try {
				if (fromClient) {
					clientMessages(micros);
				}
				else {
					serverMessages(micros);
				}
			}
			catch (RuntimeException malformed) {
				die();
			}
		}

		private void die() {
			dead = true;
			unreadable++;
		}

		private void clientMessages(long micros) {
			while (!dead) {
				if (!started) {
					if (client.available() < 8) {
						return;
					}
					int length = client.peekInt(0);
					if (length < 8 || length > MAX_MESSAGE) {
						die();
						return;
					}
					if (client.available() < length) {
						return;
					}
					ByteBuffer b = client.take(length);
					b.getInt();
					int code = b.getInt();
					if (code == SSL_REQUEST || code == GSS_REQUEST) {
						awaitingSslAnswer = true;
						continue;
					}
					if (code != PROTOCOL_3) {
						die();
						return;
					}
					startup(b);
					started = true;
					continue;
				}
				if (client.available() < 5) {
					return;
				}
				int length = client.peekInt(1);
				if (length < 4 || length > MAX_MESSAGE) {
					die();
					return;
				}
				if (client.available() < 1 + length) {
					return;
				}
				ByteBuffer b = client.take(1 + length);
				char type = (char) b.get();
				b.getInt();
				clientMessage(type, b, micros);
			}
		}

		private void startup(ByteBuffer b) {
			while (b.hasRemaining()) {
				String key = cstring(b);
				if (key.isEmpty()) {
					break;
				}
				String value = cstring(b);
				if (key.equals("user")) {
					user = value;
				}
				else if (key.equals("database")) {
					database = value;
				}
			}
			if (database == null) {
				database = user;
			}
		}

		private void clientMessage(char type, ByteBuffer b, long micros) {
			switch (type) {
				case 'Q' -> pending.add(new Pending(true, cstring(b), micros));
				case 'P' -> {
					String name = cstring(b);
					String sql = cstring(b);
					int[] types = new int[b.getShort() & 0xffff];
					for (int i = 0; i < types.length; i++) {
						types[i] = b.getInt();
					}
					statements.put(name, new Statement(sql, types));
				}
				case 'B' -> bind(b);
				case 'E' -> {
					String sql = portals.get(cstring(b));
					if (sql == null) {
						die(); // 캡처 전에 만든 문장
						return;
					}
					pending.add(new Pending(false, sql, micros));
				}
				case 'X' -> close(micros);
				default -> { } // S, H, D, C, p(인증), d, c, f(COPY)
			}
		}

		/** 인자를 csvlog의 parameters: 꼴로 만들어 문장에 끼워 넣고, 포털 이름에 붙여 둔다. */
		private void bind(ByteBuffer b) {
			String portal = cstring(b);
			Statement statement = statements.get(cstring(b));
			if (statement == null) {
				die();
				return;
			}
			int[] formats = new int[b.getShort() & 0xffff];
			for (int i = 0; i < formats.length; i++) {
				formats[i] = b.getShort();
			}
			int count = b.getShort() & 0xffff;
			StringBuilder detail = new StringBuilder("parameters: ");
			for (int i = 0; i < count; i++) {
				int length = b.getInt();
				String value;
				if (length < 0) {
					value = "NULL";
				}
				else {
					if (length > b.remaining()) {
						throw new IllegalArgumentException("인자의 길이가 메시지보다 길다");
					}
					byte[] bytes = new byte[length];
					b.get(bytes);
					int format = formats.length == 0 ? 0 : formats.length == 1 ? formats[0] : formats[i];
					int type = i < statement.types().length ? statement.types()[i] : 0;
					value = format == 0 ? quote(new String(bytes, StandardCharsets.UTF_8)) : binary(bytes, type);
				}
				detail.append(i == 0 ? "" : ", ").append('$').append(i + 1).append(" = ").append(value);
			}
			portals.put(portal, count == 0 ? statement.sql() : PgParameters.inline(statement.sql(), detail.toString()));
		}

		private void serverMessages(long micros) {
			if (awaitingSslAnswer) {
				if (server.available() < 1) {
					return;
				}
				byte answer = server.take(1).get();
				awaitingSslAnswer = false;
				if (answer == 'S' || answer == 'G') {
					die(); // 암호화로 넘어간다
					return;
				}
			}
			while (!dead && server.available() >= 5) {
				int length = server.peekInt(1);
				if (length < 4 || length > MAX_MESSAGE) {
					die();
					return;
				}
				if (server.available() < 1 + length) {
					return;
				}
				ByteBuffer b = server.take(1 + length);
				char type = (char) b.get();
				b.getInt();
				serverMessage(type, b, micros);
			}
		}

		private void serverMessage(char type, ByteBuffer b, long micros) {
			switch (type) {
				case 'K' -> session = Integer.toString(b.getInt()); // 백엔드의 프로세스 번호
				case 'C', 's' -> {
					Pending head = pending.peek();
					if (head != null && !head.simple) {
						pending.poll();
						emit(head, micros, false);
					}
				}
				case 'I' -> {
					Pending head = pending.peek();
					if (head != null && !head.simple) {
						pending.poll(); // 빈 문장
					}
				}
				case 'E' -> {
					Pending head = pending.peek();
					if (head == null) {
						return;
					}
					if (head.simple) {
						head.failed = true;
					}
					else {
						pending.poll();
						emit(head, micros, true);
						skipUntilReady = true; // 서버는 Sync까지 나머지를 버린다
					}
				}
				case 'Z' -> {
					Pending head = pending.peek();
					if (head != null && head.simple) {
						pending.poll();
						emit(head, micros, head.failed);
					}
					else if (skipUntilReady) {
						// 오류 뒤에 버려진 Execute들. 실행되지 않았다
						pending.removeIf(p -> !p.simple);
					}
					skipUntilReady = false;
				}
				default -> { } // R, S, 1, 2, n, t, T, D, N, A, G, H, W
			}
		}

		private void emit(Pending p, long micros, boolean failed) {
			ready.add(new Entry(session, p.startMicros, Math.max(0, micros - p.startMicros), p.sql, database, user,
					failed ? CsvLogConverter.FAILED : 0, false));
		}

		@Override
		public void close(long micros) {
			if (!closed && started) {
				closed = true;
				ready.add(new Entry(session, micros, 0, null, database, user, null, true));
			}
			dead = true;
		}

		@Override
		public void lost(long micros) {
			close(micros);
		}
	}

	static String quote(String text) {
		return "'" + text.replace("'", "''") + "'";
	}

	/** 바이너리 인자. 자료형을 밝힌 정수, 실수, 참거짓만 읽고 나머지는 bytea 꼴로 둔다. */
	static String binary(byte[] bytes, int type) {
		ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
		return switch (type) {
			case 16 -> bytes.length == 1 ? (bytes[0] != 0 ? "'t'" : "'f'") : hex(bytes);
			case 21 -> bytes.length == 2 ? quote(Short.toString(b.getShort())) : hex(bytes);
			case 23 -> bytes.length == 4 ? quote(Integer.toString(b.getInt())) : hex(bytes);
			case 20 -> bytes.length == 8 ? quote(Long.toString(b.getLong())) : hex(bytes);
			case 700 -> bytes.length == 4 ? quote(Float.toString(b.getFloat())) : hex(bytes);
			case 701 -> bytes.length == 8 ? quote(Double.toString(b.getDouble())) : hex(bytes);
			default -> hex(bytes);
		};
	}

	private static String hex(byte[] bytes) {
		return "'\\x" + HexFormat.of().formatHex(bytes) + "'";
	}

	static String cstring(ByteBuffer b) {
		int start = b.position();
		while (b.get() != 0) {
			// 0을 찾는다
		}
		byte[] bytes = new byte[b.position() - start - 1];
		b.get(start, bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/** 한 방향의 이어진 바이트. 다 쓴 앞부분은 버린다. */
	static final class Bytes {

		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		private byte[] data = new byte[0];
		private int position;

		void add(byte[] bytes) {
			buffer.reset();
			buffer.write(data, position, data.length - position);
			buffer.writeBytes(bytes);
			data = buffer.toByteArray();
			position = 0;
		}

		int available() {
			return data.length - position;
		}

		int peekInt(int offset) {
			return ByteBuffer.wrap(data, position + offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
		}

		ByteBuffer take(int length) {
			ByteBuffer b = ByteBuffer.wrap(java.util.Arrays.copyOfRange(data, position, position + length)).order(ByteOrder.BIG_ENDIAN);
			position += length;
			return b;
		}
	}
}
