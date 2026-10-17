package io.dbreplay.convert;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * {@code tcpdump -w}가 쓰는 pcap 파일을 읽어 TCP 조각을 하나씩 낸다(#79). 서버 로그를 켜지 않고 녹화하려는 것이다.
 * 두 기종의 패킷 캡처 변환기가 함께 쓴다. 프로토콜은 여기서 보지 않는다.
 *
 * <p>읽는 것: pcap(마이크로초와 나노초, 두 바이트 순서), 링크 계층은 이더넷, 리눅스 SLL과 SLL2, BSD 루프백, 날 IP.
 * IPv4와 IPv6 위의 TCP. pcapng는 읽지 않는다({@code tcpdump -w}의 기본이 pcap이다). IP 조각 모음은 하지 않는다.
 */
public final class PcapReader {

	/** TCP 조각 하나. 시각은 UTC 에포크 마이크로초다. */
	public record Segment(long micros, String srcHost, int srcPort, String dstHost, int dstPort, long seq, boolean syn,
			boolean fin, boolean rst, byte[] payload) {
	}

	private static final int ETHERNET = 1;
	private static final int NULL_LOOPBACK = 0;
	private static final int RAW = 101;
	private static final int RAW_OLD = 12;
	private static final int LINUX_SLL = 113;
	private static final int LINUX_SLL2 = 276;
	/** 패킷 하나의 길이로 믿을 수 있는 상한. tcpdump의 기본 스냅 길이(262,144)다. 넘으면 파일이 깨진 것으로 본다 */
	static final int MAX_PACKET = 262_144;

	private final DataInputStream in;
	private final ByteOrder order;
	private final boolean nanos;
	private final int linkType;
	private long skipped;

	public PcapReader(InputStream in) throws IOException {
		this.in = new DataInputStream(in);
		byte[] header = this.in.readNBytes(24);
		if (header.length < 24) {
			throw new IOException("pcap 파일이 아니다(머리가 짧다)");
		}
		int magic = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
		switch (magic) {
			case 0xa1b2c3d4 -> { order = ByteOrder.BIG_ENDIAN; nanos = false; }
			case 0xd4c3b2a1 -> { order = ByteOrder.LITTLE_ENDIAN; nanos = false; }
			case 0xa1b23c4d -> { order = ByteOrder.BIG_ENDIAN; nanos = true; }
			case 0x4d3cb2a1 -> { order = ByteOrder.LITTLE_ENDIAN; nanos = true; }
			default -> throw new IOException("pcap 파일이 아니다. pcapng는 읽지 않는다. tcpdump -w로 뜬 파일을 준다");
		}
		this.linkType = ByteBuffer.wrap(header, 20, 4).order(order).getInt() & 0x0fff_ffff;
		if (linkType != ETHERNET && linkType != NULL_LOOPBACK && linkType != RAW && linkType != RAW_OLD && linkType != LINUX_SLL
				&& linkType != LINUX_SLL2) {
			throw new IOException("읽지 않는 링크 계층이다: " + linkType);
		}
	}

	/** TCP가 아니거나 잘려서 읽지 못한 패킷 수. */
	public long skipped() {
		return skipped;
	}

	/** 다음 TCP 조각. 끝이면 null. */
	public Segment next() throws IOException {
		while (true) {
			byte[] record = in.readNBytes(16);
			if (record.length == 0) {
				return null;
			}
			if (record.length < 16) {
				throw new EOFException("pcap 파일이 레코드 중간에서 끝났다");
			}
			ByteBuffer r = ByteBuffer.wrap(record).order(order);
			long seconds = r.getInt() & 0xffff_ffffL;
			long fraction = r.getInt() & 0xffff_ffffL;
			int captured = r.getInt();
			int original = r.getInt();
			// 깨졌거나 조작된 파일의 길이를 믿고 메모리를 잡지 않는다(커밋 뒤 보안 검토의 지적)
			if (captured < 0 || captured > MAX_PACKET) {
				throw new IOException("pcap 레코드의 길이가 이상하다: " + captured + ". 파일이 깨졌다");
			}
			byte[] packet = in.readNBytes(captured);
			if (packet.length < captured) {
				throw new EOFException("pcap 파일이 패킷 중간에서 끝났다");
			}
			long micros = seconds * 1_000_000 + (nanos ? fraction / 1_000 : fraction);
			Segment s = captured < original ? null : parse(packet, micros);
			if (s != null) {
				return s;
			}
			skipped++;
		}
	}

	private Segment parse(byte[] packet, long micros) {
		ByteBuffer b = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
		int ipStart;
		switch (linkType) {
			case ETHERNET -> {
				if (packet.length < 14) {
					return null;
				}
				int type = b.getShort(12) & 0xffff;
				ipStart = 14;
				if (type == 0x8100) { // VLAN 꼬리표
					type = b.getShort(16) & 0xffff;
					ipStart = 18;
				}
				if (type != 0x0800 && type != 0x86dd) {
					return null;
				}
			}
			case LINUX_SLL -> ipStart = 16;
			case LINUX_SLL2 -> ipStart = 20;
			case NULL_LOOPBACK -> ipStart = 4;
			default -> ipStart = 0;
		}
		if (packet.length <= ipStart) {
			return null;
		}
		int version = (packet[ipStart] & 0xff) >> 4;
		String src, dst;
		int tcpStart, ipEnd;
		if (version == 4) {
			int ihl = (packet[ipStart] & 0x0f) * 4;
			if (packet.length < ipStart + ihl || packet[ipStart + 9] != 6) {
				return null;
			}
			int flagsFragment = b.getShort(ipStart + 6) & 0xffff;
			if ((flagsFragment & 0x1fff) != 0 || (flagsFragment & 0x2000) != 0) {
				return null; // IP 조각. 모으지 않는다
			}
			ipEnd = ipStart + (b.getShort(ipStart + 2) & 0xffff);
			src = ipv4(packet, ipStart + 12);
			dst = ipv4(packet, ipStart + 16);
			tcpStart = ipStart + ihl;
		}
		else if (version == 6) {
			if (packet.length < ipStart + 40 || packet[ipStart + 6] != 6) {
				return null; // 확장 헤더를 따라가지 않는다
			}
			ipEnd = ipStart + 40 + (b.getShort(ipStart + 4) & 0xffff);
			src = ipv6(b, ipStart + 8);
			dst = ipv6(b, ipStart + 24);
			tcpStart = ipStart + 40;
		}
		else {
			return null;
		}
		ipEnd = Math.min(ipEnd, packet.length);
		if (ipEnd < tcpStart + 20) {
			return null;
		}
		int srcPort = b.getShort(tcpStart) & 0xffff;
		int dstPort = b.getShort(tcpStart + 2) & 0xffff;
		long seq = b.getInt(tcpStart + 4) & 0xffff_ffffL;
		int dataOffset = ((packet[tcpStart + 12] & 0xff) >> 4) * 4;
		int flags = packet[tcpStart + 13] & 0xff;
		int payloadStart = tcpStart + dataOffset;
		if (payloadStart > ipEnd) {
			return null;
		}
		byte[] payload = java.util.Arrays.copyOfRange(packet, payloadStart, ipEnd);
		return new Segment(micros, src, srcPort, dst, dstPort, seq, (flags & 0x02) != 0, (flags & 0x01) != 0, (flags & 0x04) != 0,
				payload);
	}

	private static String ipv4(byte[] p, int at) {
		return (p[at] & 0xff) + "." + (p[at + 1] & 0xff) + "." + (p[at + 2] & 0xff) + "." + (p[at + 3] & 0xff);
	}

	private static String ipv6(ByteBuffer b, int at) {
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < 8; i++) {
			s.append(i == 0 ? "" : ":").append(Integer.toHexString(b.getShort(at + 2 * i) & 0xffff));
		}
		return s.toString();
	}
}
