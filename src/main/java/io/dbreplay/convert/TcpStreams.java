package io.dbreplay.convert;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * TCP 조각을 연결마다, 방향마다 순서대로 이어 프로토콜 해석기에 넘긴다(#79). 두 기종이 함께 쓴다.
 *
 * <p>시작(SYN)을 본 연결만 다룬다. 캡처를 켜기 전부터 열려 있던 연결은 어디서부터 메시지가 시작하는지 알 수 없다.
 * 이어지지 않는 구간(캡처가 놓친 조각)이 생기면 메우지 않고 그 연결을 놓쳤다고 알린 뒤 더 넘기지 않는다. 구간이 메워지기를
 * 기다리는 동안 반대쪽이 데이터를 보내면 놓친 것으로 본다. DB 프로토콜은 요청과 응답이 번갈아 가서, 응답이 왔다는 것은
 * 요청이 다 갔다는 뜻이다. 캡처가 끝날 때 구간이 남은 연결도 놓친 것으로 본다.
 * 틀린 문장을 지어내기보다 빠뜨렸다고 알리는 쪽을 골랐다. 재전송과 겹친 조각은 이미 넘긴 부분을 잘라 낸다.
 */
public final class TcpStreams {

	/** 연결 하나의 해석기. */
	public interface Conversation {
		/** 순서대로 이어진 바이트. */
		void data(boolean fromClient, byte[] bytes, long micros);

		/** FIN이나 RST로 닫혔다. */
		void close(long micros);

		/** 이어지지 않는 구간이 생겨 더 넘기지 않는다. */
		void lost(long micros);
	}

	/** 새 연결이 열릴 때 해석기를 만든다. */
	@FunctionalInterface
	public interface Factory {
		Conversation open(String id, String clientHost, long micros);
	}

	/** 순서가 어긋난 조각을 이만큼까지만 들고 기다린다. 넘으면 놓친 것으로 본다. */
	private static final int MAX_PENDING = 1_024;

	private static final class Direction {
		long next = -1;
		final TreeMap<Long, PcapReader.Segment> pending = new TreeMap<>();
	}

	private static final class Connection {
		final Conversation conversation;
		final Direction client = new Direction();
		final Direction server = new Direction();
		boolean done;

		Connection(Conversation conversation) {
			this.conversation = conversation;
		}
	}

	private final int serverPort;
	private final Factory factory;
	private final Map<String, Connection> connections = new HashMap<>();
	private final Set<String> unseenStart = new HashSet<>();
	// 놓쳐서 해석을 멈춘 연결. 뒤에 오는 조각을 "시작을 보지 못한 연결"로 다시 세지 않으려고 기억한다(실험 19)
	private final Set<String> abandoned = new HashSet<>();
	private long opened;
	private long lost;

	/** @param serverPort DB 서버의 포트. 이 포트로 가는 쪽이 클라이언트다 */
	public TcpStreams(int serverPort, Factory factory) {
		this.serverPort = serverPort;
		this.factory = factory;
	}

	public long opened() {
		return opened;
	}

	/** 시작을 보지 못해 다루지 않은 연결 수. 캡처 전부터 열려 있던 연결이다. */
	public long unseenStart() {
		return unseenStart.size();
	}

	/** 조각을 놓쳐 도중에 해석을 멈춘 연결 수. */
	public long lost() {
		return lost;
	}

	public void accept(PcapReader.Segment s) {
		boolean fromClient = s.dstPort() == serverPort;
		if (!fromClient && s.srcPort() != serverPort) {
			return;
		}
		String key = fromClient
				? s.srcHost() + ":" + s.srcPort() + ">" + s.dstHost() + ":" + s.dstPort()
				: s.dstHost() + ":" + s.dstPort() + ">" + s.srcHost() + ":" + s.srcPort();
		Connection c = connections.get(key);
		if (c == null && abandoned.contains(key)) {
			if (!(fromClient && s.syn())) {
				return;
			}
			abandoned.remove(key); // 같은 주소와 포트로 새 연결이 열렸다
		}
		if (c == null) {
			if (!(fromClient && s.syn())) {
				if (s.payload().length > 0) {
					unseenStart.add(key);
				}
				return;
			}
			c = new Connection(factory.open(Long.toString(++opened), fromClient ? s.srcHost() : s.dstHost(), s.micros()));
			connections.put(key, c);
		}
		if (c.done) {
			return;
		}
		Direction d = fromClient ? c.client : c.server;
		if (s.syn()) {
			d.next = (s.seq() + 1) & 0xffff_ffffL;
			return;
		}
		if (d.next < 0) {
			lose(key, c, s.micros()); // SYN-ACK을 놓쳤다
			return;
		}
		if (s.payload().length > 0) {
			Direction other = fromClient ? c.server : c.client;
			if (!other.pending.isEmpty()) {
				lose(key, c, s.micros());
				return;
			}
			d.pending.put(s.seq(), s);
			if (!drain(c, d, fromClient)) {
				lose(key, c, s.micros());
				return;
			}
		}
		if (s.rst() || (s.fin() && d.pending.isEmpty())) {
			c.done = true;
			connections.remove(key);
			c.conversation.close(s.micros());
		}
	}

	/** 이어지는 조각을 넘긴다. 기다리는 조각이 너무 많으면 거짓. */
	private static boolean drain(Connection c, Direction d, boolean fromClient) {
		while (!d.pending.isEmpty()) {
			PcapReader.Segment first = null;
			for (PcapReader.Segment s : d.pending.values()) {
				if (diff(s.seq(), d.next) <= 0) {
					first = s;
					break;
				}
			}
			if (first == null) {
				return d.pending.size() <= MAX_PENDING;
			}
			d.pending.remove(first.seq());
			int skip = (int) -diff(first.seq(), d.next);
			if (skip < first.payload().length) {
				byte[] fresh = skip == 0 ? first.payload() : java.util.Arrays.copyOfRange(first.payload(), skip, first.payload().length);
				d.next = (d.next + fresh.length) & 0xffff_ffffL;
				c.conversation.data(fromClient, fresh, first.micros());
			}
		}
		return true;
	}

	/** 캡처가 끝났다. 메워지지 않은 구간이 남은 연결은 놓친 것으로 본다. */
	public void finish(long micros) {
		for (Map.Entry<String, Connection> e : new java.util.ArrayList<>(connections.entrySet())) {
			if (!e.getValue().client.pending.isEmpty() || !e.getValue().server.pending.isEmpty()) {
				lose(e.getKey(), e.getValue(), micros);
			}
		}
	}

	/** 32비트 순번의 부호 있는 차이. 순번이 한 바퀴 돌아도 맞게 비교한다. */
	static long diff(long a, long b) {
		return (int) (a - b);
	}

	private void lose(String key, Connection c, long micros) {
		c.done = true;
		connections.remove(key);
		abandoned.add(key);
		lost++;
		c.conversation.lost(micros);
	}
}
