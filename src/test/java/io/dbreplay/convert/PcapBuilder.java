package io.dbreplay.convert;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 테스트에서 쓰는 pcap 파일. 이더넷, IPv4, TCP 한 연결을 만든다. 순번은 방향마다 알아서 늘린다. */
public final class PcapBuilder {

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();
	private final int serverPort;
	private int clientPort = 50000;
	private long clientSeq = 1_000;
	private long serverSeq = 5_000;

	public PcapBuilder(int serverPort) {
		this.serverPort = serverPort;
		ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
		h.putInt(0xa1b2c3d4).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(65535).putInt(1);
		out.writeBytes(h.array());
	}

	/** 새 연결을 연다(SYN과 SYN-ACK). */
	public PcapBuilder open(long micros, int port) {
		clientPort = port;
		segment(micros, true, 0x02, new byte[0]);
		clientSeq++;
		segment(micros, false, 0x12, new byte[0]);
		serverSeq++;
		return this;
	}

	public PcapBuilder client(long micros, byte[] payload) {
		segment(micros, true, 0x18, payload);
		clientSeq += payload.length;
		return this;
	}

	public PcapBuilder server(long micros, byte[] payload) {
		segment(micros, false, 0x18, payload);
		serverSeq += payload.length;
		return this;
	}

	/** 클라이언트 쪽 순번을 건너뛴다. 캡처가 조각을 놓친 것처럼 만든다. */
	public PcapBuilder skipClientBytes(int n) {
		clientSeq += n;
		return this;
	}

	public PcapBuilder fin(long micros) {
		segment(micros, true, 0x11, new byte[0]);
		return this;
	}

	private void segment(long micros, boolean fromClient, int flags, byte[] payload) {
		ByteBuffer p = ByteBuffer.allocate(14 + 20 + 20 + payload.length).order(ByteOrder.BIG_ENDIAN);
		p.put(new byte[12]).putShort((short) 0x0800);
		p.put((byte) 0x45).put((byte) 0).putShort((short) (40 + payload.length)).putShort((short) 0).putShort((short) 0)
				.put((byte) 64).put((byte) 6).putShort((short) 0);
		p.put(fromClient ? new byte[] {10, 0, 0, 2} : new byte[] {10, 0, 0, 1});
		p.put(fromClient ? new byte[] {10, 0, 0, 1} : new byte[] {10, 0, 0, 2});
		p.putShort((short) (fromClient ? clientPort : serverPort)).putShort((short) (fromClient ? serverPort : clientPort));
		p.putInt((int) (fromClient ? clientSeq : serverSeq)).putInt(0);
		p.put((byte) 0x50).put((byte) flags).putShort((short) 65535).putShort((short) 0).putShort((short) 0);
		p.put(payload);
		byte[] packet = p.array();
		ByteBuffer r = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
		r.putInt((int) (micros / 1_000_000)).putInt((int) (micros % 1_000_000)).putInt(packet.length).putInt(packet.length);
		out.writeBytes(r.array());
		out.writeBytes(packet);
	}

	public InputStream stream() {
		return new ByteArrayInputStream(out.toByteArray());
	}
}
