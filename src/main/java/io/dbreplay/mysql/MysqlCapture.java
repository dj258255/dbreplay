package io.dbreplay.mysql;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

import io.dbreplay.convert.PcapReader;
import io.dbreplay.convert.TcpStreams;

/**
 * 패킷 캡처에서 MySQL 클라이언트-서버 프로토콜을 해석해 슬로우 로그 항목으로 낸다(#79). 서버가 문장마다 로그를 쓰지 않는다.
 *
 * <p>다루는 것: 인사와 로그인(계정과 처음 데이터베이스), {@code COM_QUERY}, {@code COM_INIT_DB}, {@code COM_STMT_PREPARE}와
 * {@code COM_STMT_EXECUTE}(바이너리 인자를 문장에 끼워 넣는다), {@code COM_QUIT}, 결과 집합과 OK/ERR, 여러 결과({@code CALL}).
 * 응답의 마지막 패킷이 온 시각까지를 걸린 시간으로 본다. 서버 안의 시간이 아니라 그 장비에서 본 왕복이다.
 * 락을 기다린 시간과 읽은 행 수는 프로토콜에 없어 모른다고(음수) 적는다.
 *
 * <p>TLS로 암호화된 연결은 읽지 못한다. 모르는 명령을 만나면 그 뒤를 믿을 수 없어 그 연결을 놓친 것으로 센다.
 */
public final class MysqlCapture implements SlowLogSource {

	// 능력 플래그
	private static final int CLIENT_CONNECT_WITH_DB = 0x8;
	private static final int CLIENT_PROTOCOL_41 = 0x200;
	private static final int CLIENT_SSL = 0x800;
	private static final int CLIENT_SECURE_CONNECTION = 0x8000;
	private static final int CLIENT_PLUGIN_AUTH_LENENC = 0x200000;
	private static final int CLIENT_DEPRECATE_EOF = 0x1000000;
	private static final int CLIENT_QUERY_ATTRIBUTES = 0x8000000;
	private static final int SERVER_MORE_RESULTS_EXISTS = 0x8;

	private static final int COM_QUIT = 0x01;
	private static final int COM_INIT_DB = 0x02;
	private static final int COM_QUERY = 0x03;
	private static final int COM_STATISTICS = 0x09;
	private static final int COM_PING = 0x0e;
	private static final int COM_CHANGE_USER = 0x11;
	private static final int COM_STMT_PREPARE = 0x16;
	private static final int COM_STMT_EXECUTE = 0x17;
	private static final int COM_STMT_SEND_LONG_DATA = 0x18;
	private static final int COM_STMT_CLOSE = 0x19;
	private static final int COM_STMT_RESET = 0x1a;
	private static final int COM_SET_OPTION = 0x1b;
	private static final int COM_RESET_CONNECTION = 0x1f;

	private final PcapReader reader;
	private final TcpStreams streams;
	private boolean finished;
	private long lastMicros;
	private final Queue<SlowLogEntry> ready = new ArrayDeque<>();
	private long unreadable;

	public MysqlCapture(InputStream pcap, int serverPort) throws IOException {
		this.reader = new PcapReader(pcap);
		this.streams = new TcpStreams(serverPort, (id, host, micros) -> new Connection());
	}

	@Override
	public SlowLogEntry next() throws IOException {
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

	/** 해석하지 못한 패킷과 조각 수. */
	@Override
	public long skipped() {
		return reader.skipped();
	}

	/** 녹화가 무엇을 놓쳤는지. 변환 끝에 알린다. */
	public String losses() {
		return String.format("시작을 보지 못한 연결 %d, 조각을 놓친 연결 %d, 읽지 못한 연결(TLS, 모르는 명령) %d (연결 %d개 가운데)",
				streams.unseenStart(), streams.lost(), unreadable, streams.opened());
	}

	private enum Phase { GREETING, LOGIN, AUTH, COMMAND, DEAD }

	/** 지금 응답을 기다리는 명령. */
	private static final class Pending {
		final int command;
		final String sql;
		final long startMicros;
		final int prepareStatement;
		int stage; // 결과 집합을 읽는 단계
		long columns;
		long rows;
		int prepareParams;
		int prepareColumns;
		String initDb;

		Pending(int command, String sql, long startMicros, int prepareStatement) {
			this.command = command;
			this.sql = sql;
			this.startMicros = startMicros;
			this.prepareStatement = prepareStatement;
		}
	}

	private record Prepared(String sql, int params, int[] types) {
	}

	private final class Connection implements TcpStreams.Conversation {

		private final Packets client = new Packets();
		private final Packets server = new Packets();
		private Phase phase = Phase.GREETING;
		private long connectionId = -1;
		private int serverCaps;
		private int caps;
		private String user;
		private String db;
		private Pending pending;
		private long nextPrepareId;
		private final Map<Integer, Prepared> prepared = new HashMap<>();
		private final Map<Long, String> prepareSql = new HashMap<>();
		private boolean closed;

		@Override
		public void data(boolean fromClient, byte[] bytes, long micros) {
			if (phase == Phase.DEAD) {
				return;
			}
			(fromClient ? client : server).add(bytes, micros);
			try {
				for (Packets.Packet p; (p = (fromClient ? client : server).next()) != null;) {
					if (fromClient) {
						clientPacket(p);
					}
					else {
						serverPacket(p);
					}
					if (phase == Phase.DEAD) {
						return;
					}
				}
			}
			catch (RuntimeException malformed) {
				// 해석하다 길이가 맞지 않는다. 이 연결의 뒤는 믿을 수 없다
				dead();
			}
		}

		private void dead() {
			phase = Phase.DEAD;
			unreadable++;
		}

		private void clientPacket(Packets.Packet p) {
			ByteBuffer b = p.body();
			switch (phase) {
				case LOGIN -> login(b);
				case AUTH -> { } // 인증 데이터를 주고받는 중이다
				case COMMAND -> command(b, p.micros());
				default -> { }
			}
		}

		private void login(ByteBuffer b) {
			caps = b.getInt();
			if ((caps & CLIENT_PROTOCOL_41) == 0) {
				dead();
				return;
			}
			if ((caps & CLIENT_SSL) != 0) {
				dead(); // TLS로 넘어간다. 읽을 수 없다
				return;
			}
			b.position(32);
			user = cstring(b);
			if ((caps & CLIENT_PLUGIN_AUTH_LENENC) != 0) {
				skip(b, (int) lenenc(b));
			}
			else if ((caps & CLIENT_SECURE_CONNECTION) != 0) {
				skip(b, b.get() & 0xff);
			}
			else {
				cstring(b);
			}
			if ((caps & CLIENT_CONNECT_WITH_DB) != 0 && b.hasRemaining()) {
				String first = cstring(b);
				db = first.isEmpty() ? null : first;
			}
			phase = Phase.AUTH;
		}

		private void command(ByteBuffer b, long micros) {
			int command = b.get() & 0xff;
			switch (command) {
				case COM_QUERY -> {
					if (negotiated(CLIENT_QUERY_ATTRIBUTES)) {
						long params = lenenc(b);
						lenenc(b); // 인자 묶음 수. 늘 1
						if (params > 0) {
							dead(); // 질의 속성이 붙은 문장. 드물다
							return;
						}
					}
					pending = new Pending(command, text(b), micros, -1);
				}
				case COM_INIT_DB -> {
					pending = new Pending(command, null, micros, -1);
					pending.initDb = text(b);
				}
				case COM_STMT_PREPARE -> {
					long id = ++nextPrepareId;
					prepareSql.put(id, text(b));
					pending = new Pending(command, null, micros, (int) id);
				}
				case COM_STMT_EXECUTE -> {
					String sql = execute(b);
					if (sql == null) {
						dead();
						return;
					}
					pending = new Pending(command, sql, micros, -1);
				}
				case COM_QUIT -> quit(micros);
				case COM_STMT_CLOSE -> prepared.remove(b.getInt());
				case COM_STMT_SEND_LONG_DATA -> dead(); // 인자를 나눠 보낸다. 끼워 넣을 수 없다
				case COM_PING, COM_STMT_RESET, COM_RESET_CONNECTION, COM_SET_OPTION, COM_STATISTICS ->
						pending = new Pending(command, null, micros, -1);
				case COM_CHANGE_USER -> {
					user = cstring(b);
					phase = Phase.AUTH;
				}
				default -> dead();
			}
		}

		private boolean negotiated(int flag) {
			return (caps & serverCaps & flag) != 0;
		}

		private void serverPacket(Packets.Packet p) {
			ByteBuffer b = p.body();
			switch (phase) {
				case GREETING -> greeting(b);
				case AUTH -> {
					int head = b.get(0) & 0xff;
					if (head == 0x00) {
						phase = Phase.COMMAND;
					}
					else if (head == 0xff) {
						dead(); // 로그인 실패
					}
				}
				case COMMAND -> {
					if (pending != null) {
						response(b, p.micros());
					}
				}
				default -> { }
			}
		}

		private void greeting(ByteBuffer b) {
			if ((b.get() & 0xff) != 10) {
				dead();
				return;
			}
			cstring(b); // 서버 버전
			connectionId = b.getInt() & 0xffff_ffffL;
			b.position(b.position() + 8 + 1); // 인증 데이터 앞부분, 채움
			int lower = b.getShort() & 0xffff;
			int upper = 0;
			if (b.remaining() >= 5) {
				b.get(); // 문자 집합
				b.getShort(); // 상태
				upper = b.getShort() & 0xffff;
			}
			serverCaps = lower | (upper << 16);
			phase = Phase.LOGIN;
		}

		/** 응답 패킷 하나. 명령이 끝났으면 항목을 낸다. */
		private void response(ByteBuffer b, long micros) {
			Pending q = pending;
			int head = b.get(0) & 0xff;
			int length = b.remaining();
			switch (q.command) {
				case COM_STMT_PREPARE -> prepareResponse(q, b, head);
				case COM_QUERY, COM_STMT_EXECUTE -> queryResponse(q, b, head, length, micros);
				case COM_INIT_DB -> {
					if (head == 0x00) {
						db = q.initDb;
					}
					pending = null;
				}
				default -> pending = null; // 핑과 재설정 같은 명령은 응답 하나로 끝난다
			}
		}

		private void queryResponse(Pending q, ByteBuffer b, int head, int length, long micros) {
			switch (q.stage) {
				case 0 -> {
					if (head == 0x00) {
						int status = okStatus(b);
						if ((status & SERVER_MORE_RESULTS_EXISTS) == 0) {
							finish(q, 0, micros);
						}
					}
					else if (head == 0xff) {
						finish(q, b.order(ByteOrder.LITTLE_ENDIAN).getShort(1) & 0xffff, micros);
					}
					else if (head == 0xfb) {
						q.stage = 3; // LOAD DATA LOCAL. 클라이언트가 파일을 보낸 뒤 OK나 ERR이 온다
					}
					else {
						b.position(0);
						q.columns = lenenc(b);
						q.stage = 1;
					}
				}
				case 1 -> { // 열 정의
					if (--q.columns == 0) {
						q.stage = negotiated(CLIENT_DEPRECATE_EOF) ? 2 : 4;
					}
				}
				case 4 -> q.stage = 2; // 열 정의 뒤의 EOF
				case 2 -> { // 행
					if (head == 0xfe && length < 0xffffff) {
						int status = negotiated(CLIENT_DEPRECATE_EOF) ? okStatus(b) : b.order(ByteOrder.LITTLE_ENDIAN).getShort(3) & 0xffff;
						if ((status & SERVER_MORE_RESULTS_EXISTS) != 0) {
							q.stage = 0;
						}
						else {
							finish(q, 0, micros);
						}
					}
					else if (head == 0xff) {
						finish(q, b.order(ByteOrder.LITTLE_ENDIAN).getShort(1) & 0xffff, micros);
					}
					else {
						q.rows++;
					}
				}
				case 3 -> {
					if (head == 0x00 || head == 0xff) {
						finish(q, head == 0xff ? b.order(ByteOrder.LITTLE_ENDIAN).getShort(1) & 0xffff : 0, micros);
					}
				}
				default -> { }
			}
		}

		private void prepareResponse(Pending q, ByteBuffer b, int head) {
			if (q.stage == 0) {
				if (head != 0x00) {
					pending = null; // 준비가 실패했다. 실행도 실패할 것이고 그때 문장이 남는다
					return;
				}
				b.order(ByteOrder.LITTLE_ENDIAN).position(1);
				int statementId = b.getInt();
				q.prepareColumns = b.getShort() & 0xffff;
				q.prepareParams = b.getShort() & 0xffff;
				prepared.put(statementId, new Prepared(prepareSql.remove((long) q.prepareStatement), q.prepareParams, null));
				q.columns = q.prepareParams + q.prepareColumns
						+ (negotiated(CLIENT_DEPRECATE_EOF) ? 0 : (q.prepareParams > 0 ? 1 : 0) + (q.prepareColumns > 0 ? 1 : 0));
				q.stage = 1;
				if (q.columns == 0) {
					pending = null;
				}
				return;
			}
			if (--q.columns == 0) {
				pending = null;
			}
		}

		/** OK 패킷의 상태 플래그. */
		private int okStatus(ByteBuffer b) {
			b.order(ByteOrder.LITTLE_ENDIAN).position(1);
			lenenc(b); // 바뀐 행
			lenenc(b); // 마지막 자동 증가 값
			return b.getShort() & 0xffff;
		}

		private void finish(Pending q, int errno, long micros) {
			pending = null;
			if (connectionId < 0) {
				return;
			}
			ready.add(new SlowLogEntry(connectionId, user, q.startMicros, Math.max(0, micros - q.startMicros), -1, q.rows, -1, errno,
					db, null, q.sql));
		}

		private void quit(long micros) {
			if (!closed && connectionId >= 0) {
				closed = true;
				ready.add(new SlowLogEntry(connectionId, user, micros, 0, -1, 0, -1, null, db, "Quit", null));
			}
			phase = Phase.DEAD;
		}

		/** {@code COM_STMT_EXECUTE}의 인자를 읽어 준비한 문장에 끼워 넣는다. 읽지 못하면 null. */
		private String execute(ByteBuffer b) {
			b.order(ByteOrder.LITTLE_ENDIAN);
			int statementId = b.getInt();
			int flags = b.get() & 0xff;
			b.getInt(); // 반복 횟수. 늘 1
			Prepared p = prepared.get(statementId);
			if (p == null || p.sql() == null) {
				return null; // 캡처 전에 준비한 문장
			}
			int count = p.params();
			if (negotiated(CLIENT_QUERY_ATTRIBUTES) && (flags & 0x08) != 0) {
				count = (int) lenenc(b);
			}
			if (count == 0) {
				return p.sql();
			}
			byte[] nulls = new byte[(count + 7) / 8];
			b.get(nulls);
			boolean bound = b.get() != 0;
			int[] types = p.types();
			if (bound) {
				types = new int[count];
				for (int i = 0; i < count; i++) {
					types[i] = b.getShort() & 0xffff;
					if (negotiated(CLIENT_QUERY_ATTRIBUTES)) {
						skip(b, (int) lenenc(b)); // 속성 이름
					}
				}
				prepared.put(statementId, new Prepared(p.sql(), p.params(), types));
			}
			if (types == null || types.length < count) {
				return null;
			}
			String[] values = new String[count];
			for (int i = 0; i < count; i++) {
				values[i] = (nulls[i / 8] & (1 << (i % 8))) != 0 ? "NULL" : BinaryValues.read(b, types[i]);
			}
			return BinaryValues.inline(p.sql(), java.util.Arrays.copyOf(values, p.params()));
		}

		@Override
		public void close(long micros) {
			quit(micros);
		}

		@Override
		public void lost(long micros) {
			quit(micros);
		}
	}

	/**
	 * 지금 자리에서 n바이트를 건너뛴다. {@code b.position(b.position() + 길이를 읽는 식)}으로 쓰면 자리를 먼저 읽고 길이를 나중에
	 * 읽어, 길이 바이트를 읽은 만큼 되돌아간다. 실제로 그렇게 틀려 처음 데이터베이스를 놓쳤다.
	 */
	static void skip(ByteBuffer b, int n) {
		if (n < 0 || n > b.remaining()) {
			throw new IllegalArgumentException("건너뛸 길이가 패킷보다 길다");
		}
		b.position(b.position() + n);
	}

	static long lenenc(ByteBuffer b) {
		int first = b.get() & 0xff;
		ByteBuffer le = b.order(ByteOrder.LITTLE_ENDIAN);
		return switch (first) {
			case 0xfc -> le.getShort() & 0xffff;
			case 0xfd -> (le.get() & 0xff) | ((le.get() & 0xff) << 8) | ((long) (le.get() & 0xff) << 16);
			case 0xfe -> le.getLong();
			default -> first;
		};
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

	/** 남은 바이트 전부를 글자로. */
	static String text(ByteBuffer b) {
		byte[] bytes = new byte[b.remaining()];
		b.get(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	/** UTF-8로 읽히면 그 글자, 아니면 null. */
	static String utf8(byte[] bytes) {
		try {
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		}
		catch (CharacterCodingException e) {
			return null;
		}
	}

	/** MySQL 패킷을 잇는다. 길이 3바이트, 순번 1바이트, 본문. 최대 길이 패킷은 다음 패킷과 이어 붙인다. */
	static final class Packets {

		record Packet(ByteBuffer body, long micros) {
		}

		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		private byte[] data = new byte[0];
		private int position;
		private long lastMicros;
		private ByteArrayOutputStream continued;

		void add(byte[] bytes, long micros) {
			buffer.reset();
			buffer.write(data, position, data.length - position);
			buffer.writeBytes(bytes);
			data = buffer.toByteArray();
			position = 0;
			lastMicros = micros;
		}

		Packet next() {
			while (data.length - position >= 4) {
				int length = (data[position] & 0xff) | ((data[position + 1] & 0xff) << 8) | ((data[position + 2] & 0xff) << 16);
				if (data.length - position - 4 < length) {
					return null;
				}
				byte[] body = java.util.Arrays.copyOfRange(data, position + 4, position + 4 + length);
				position += 4 + length;
				if (length == 0xffffff) {
					if (continued == null) {
						continued = new ByteArrayOutputStream();
					}
					continued.writeBytes(body);
					continue;
				}
				if (continued != null) {
					continued.writeBytes(body);
					body = continued.toByteArray();
					continued = null;
				}
				return new Packet(ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN), lastMicros);
			}
			return null;
		}
	}
}
