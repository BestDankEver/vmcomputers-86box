package mcvmcomputers.client.screen;

import java.util.HashMap;
import java.util.Map;

import mcvmcomputers.emu.VmSession;
import mcvmcomputers.networking.PacketList;
import mcvmcomputers.stream.AudioCodec;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;

/**
 * Owner side of sharing VM sound with nearby players: each 86Box stream that
 * is making sound is compressed ({@link AudioCodec}) and sent to the server
 * about ten times a second; the server passes it to players near the PC.
 * Streams that have been silent for a second aren't sent.
 */
public final class AudioShare {
	private AudioShare() {
	}

	private static final int SEND_MS = 100;
	private static final long QUIET_MS = 1000;

	private static final class Pending {
		final AudioCodec.Encoder encoder = new AudioCodec.Encoder();
		short[] buf = new short[4800];
		int len;
		int rate;
		long lastSound;
	}

	private static final Map<Integer, Pending> streams = new HashMap<>();

	/** Called with every piece of sound from this player's VM (render thread). */
	public static void owner(VmSession.AudioChunk c) {
		if (!ScreenStream.othersCanWatch()) {
			return;
		}
		long now = System.currentTimeMillis();
		Pending p = streams.computeIfAbsent(c.stream, k -> new Pending());
		if (!c.silent) {
			p.lastSound = now;
		}
		if (now - p.lastSound > QUIET_MS || p.rate != c.rate) {
			p.len = 0;
			p.rate = c.rate;
			if (now - p.lastSound > QUIET_MS) {
				return;
			}
		}
		if (p.len + c.mono.length > p.buf.length) {
			short[] b = new short[Math.max(p.buf.length * 2, p.len + c.mono.length)];
			System.arraycopy(p.buf, 0, b, 0, p.len);
			p.buf = b;
		}
		System.arraycopy(c.mono, 0, p.buf, p.len, c.mono.length);
		p.len += c.mono.length;
		if (p.len * 1000L / Math.max(1, p.rate) < SEND_MS) {
			return;
		}
		short[] out = new short[p.len];
		System.arraycopy(p.buf, 0, out, 0, p.len);
		p.len = 0;
		byte[] data = p.encoder.encode(out, p.rate);
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.getNetworkHandler() != null && data.length <= PacketList.MAX_AUDIO_PACKET) {
			PacketByteBuf b = PacketByteBufs.create();
			b.writeInt(c.stream);
			b.writeByteArray(data);
			ClientPlayNetworking.send(PacketList.C2S_AUDIO, b);
		}
	}

	public static void reset() {
		streams.clear();
	}
}
