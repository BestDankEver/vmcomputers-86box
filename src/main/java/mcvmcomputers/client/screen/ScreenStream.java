package mcvmcomputers.client.screen;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import mcvmcomputers.emu.VmSession;
import mcvmcomputers.networking.PacketList;
import mcvmcomputers.stream.FrameCodec;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.network.PacketByteBuf;

/**
 * Sharing VM screens with other players (watch only).
 *
 * Owner side: a few times a second the newest frame is encoded and sent to
 * the server in chunks, which relays them to players near the PC. Unchanged
 * screens aren't resent, except for a refresh every few seconds so players who
 * walk up later get a picture.
 *
 * Watcher side: chunks are reassembled per owner and decoded off the render
 * thread; the result is shown on the owner's monitors.
 */
public final class ScreenStream {
	private ScreenStream() {
	}

	private static final ExecutorService ENCODER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "VM screen encoder");
		t.setDaemon(true);
		return t;
	});
	private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "VM screen decoder");
		t.setDaemon(true);
		return t;
	});

	// ---- Owner side ------------------------------------------------------------------

	private static final AtomicInteger frameIds = new AtomicInteger();
	private static long lastSentSerial = -1;
	private static int lastSentHash;
	private static long lastSentAt;
	private static long nextSendAt;
	private static volatile boolean encoding;
	private static final long REFRESH_MS = 3000;

	/** Called every client frame while the player's VM runs. */
	public static void ownerTick(VmSession vm, int fps) {
		if (vm == null || fps <= 0 || encoding || !othersCanWatch()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now < nextSendAt) {
			return;
		}
		VmSession.Frame f = vm.latestFrame();
		if (f == null) {
			return;
		}
		boolean refresh = now - lastSentAt > REFRESH_MS;
		if (f.serial == lastSentSerial && !refresh) {
			return;
		}
		int hash = Arrays.hashCode(f.png);
		lastSentSerial = f.serial;
		if (hash == lastSentHash && !refresh) {
			return; // screen didn't change
		}
		nextSendAt = now + 1000 / fps;
		lastSentHash = hash;
		lastSentAt = now;
		encoding = true;
		ENCODER.execute(() -> {
			try {
				FrameCodec.Encoded e = FrameCodec.encode(f.png, f.width, f.height, 0.7f);
				int id = frameIds.incrementAndGet();
				MinecraftClient mc = MinecraftClient.getInstance();
				for (byte[] chunk : FrameCodec.chunks(e, id, PacketList.MAX_SCREEN_CHUNK)) {
					mc.execute(() -> {
						if (mc.getNetworkHandler() != null) {
							PacketByteBuf b = PacketByteBufs.create();
							b.writeBytes(chunk);
							ClientPlayNetworking.send(PacketList.C2S_SCREEN, b);
						}
					});
				}
			} catch (Exception ex) {
				// Skip this frame.
			} finally {
				encoding = false;
			}
		});
	}

	public static void ownerStopped() {
		lastSentSerial = -1;
		lastSentHash = 0;
		lastSentAt = 0;
	}

	/** Only bother encoding when someone else could be watching. */
	static boolean othersCanWatch() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.getServer() != null) {
			return mc.getServer().getCurrentPlayerCount() > 1; // singleplayer, or LAN with guests
		}
		return mc.getNetworkHandler() != null;
	}

	// ---- Watcher side ----------------------------------------------------------------

	private static final Map<UUID, FrameCodec.Assembler> assemblers = new HashMap<>();
	/** Decoded frames waiting for the render thread. */
	private static final Map<UUID, NativeImage> ready = new ConcurrentHashMap<>();
	private static final Map<UUID, Integer> pendingDecodes = new ConcurrentHashMap<>();

	/** Network thread: one chunk from the server. */
	public static void onChunk(UUID owner, byte[] chunk) {
		FrameCodec.Encoded done;
		synchronized (assemblers) {
			done = assemblers.computeIfAbsent(owner, k -> new FrameCodec.Assembler()).add(chunk);
		}
		if (done == null) {
			return;
		}
		int ticket = pendingDecodes.merge(owner, 1, Integer::sum);
		DECODER.execute(() -> {
			if (pendingDecodes.getOrDefault(owner, 0) != ticket) {
				return; // a newer frame is already queued
			}
			try {
				NativeImage img = decode(done);
				NativeImage old = ready.put(owner, img);
				if (old != null) {
					old.close();
				}
			} catch (Exception ex) {
				// Bad frame; wait for the next.
			}
		});
	}

	private static NativeImage decode(FrameCodec.Encoded e) throws Exception {
		if (e.format == FrameCodec.PNG) {
			return NativeImage.read(new ByteArrayInputStream(e.data));
		}
		int[] size = new int[2];
		int[] argb = FrameCodec.decodeArgb(e.data, size);
		NativeImage img = new NativeImage(size[0], size[1], false);
		for (int y = 0; y < size[1]; y++) {
			int row = y * size[0];
			for (int x = 0; x < size[0]; x++) {
				int c = argb[row + x];
				// NativeImage wants ABGR.
				img.setPixelColor(x, y, 0xff000000 | ((c & 0xff) << 16) | (c & 0xff00) | ((c >> 16) & 0xff));
			}
		}
		return img;
	}

	/** The local VM's own frames: decoded off the render thread like received ones. */
	public static void decodeLocal(UUID owner, byte[] png) {
		int ticket = pendingDecodes.merge(owner, 1, Integer::sum);
		DECODER.execute(() -> {
			if (pendingDecodes.getOrDefault(owner, 0) != ticket) {
				return;
			}
			try {
				NativeImage img = NativeImage.read(new ByteArrayInputStream(png));
				NativeImage old = ready.put(owner, img);
				if (old != null) {
					old.close();
				}
			} catch (Exception ex) {
				// skip
			}
		});
	}

	/** Render thread: takes the newest decoded frame for an owner, if any. */
	public static NativeImage takeReady(UUID owner) {
		return ready.remove(owner);
	}

	public static Iterable<UUID> readyOwners() {
		return ready.keySet();
	}

	public static void forget(UUID owner) {
		synchronized (assemblers) {
			assemblers.remove(owner);
		}
		pendingDecodes.remove(owner);
		NativeImage img = ready.remove(owner);
		if (img != null) {
			img.close();
		}
	}
}
