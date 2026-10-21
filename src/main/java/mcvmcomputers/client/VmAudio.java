package mcvmcomputers.client;

import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;

/**
 * Plays VM sound from the PC itself, through Minecraft's own OpenAL context:
 * it gets quieter with distance and pans as you turn, like any other sound.
 *
 * Each running PC (by owner) has one OpenAL source per 86Box output stream
 * (main mix, CD audio, MIDI, ...); OpenAL resamples and mixes them. The
 * volume follows the "Jukebox/Note Blocks" slider.
 *
 * Everything here runs on the render thread; packets from other players are
 * queued and picked up there.
 */
public final class VmAudio {
	/** Blocks until the sound fades out completely. */
	private static final float RANGE = 24f;
	/** Sound to collect before (re)starting a stream, and the most to queue before dropping (keeps the delay low). */
	private static final int START_MS = 90;
	private static final int MAX_MS = 600;

	private VmAudio() {
	}

	private static final class Stream {
		final int source;
		final ArrayDeque<int[]> queued = new ArrayDeque<>(); // {buffer, ms}
		int queuedMs;
		long lastData;

		Stream(int source) {
			this.source = source;
		}
	}

	private static final class Pc {
		int entityId;
		final Map<Integer, Stream> streams = new HashMap<>();
	}

	private static final class Incoming {
		final UUID owner;
		final int entityId;
		final int stream;
		final int rate;
		final short[] mono;

		Incoming(UUID owner, int entityId, int stream, int rate, short[] mono) {
			this.owner = owner;
			this.entityId = entityId;
			this.stream = stream;
			this.rate = rate;
			this.mono = mono;
		}
	}

	private static final Map<UUID, Pc> pcs = new HashMap<>();
	private static final ConcurrentLinkedQueue<Incoming> incoming = new ConcurrentLinkedQueue<>();
	private static boolean broken;

	/** Sound from another player's PC (any thread). */
	public static void receive(UUID owner, int entityId, int stream, int rate, short[] mono) {
		if (incoming.size() < 400) {
			incoming.add(new Incoming(owner, entityId, stream, rate, mono));
		}
	}

	/** Sound from this player's own PC (render thread). */
	public static void play(UUID owner, int entityId, int stream, int rate, short[] mono, boolean silent) {
		if (broken) {
			return;
		}
		try {
			Pc pc = pcs.computeIfAbsent(owner, k -> new Pc());
			pc.entityId = entityId;
			Stream s = pc.streams.get(stream);
			if (silent && (s == null || AL10.alGetSourcei(s.source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING)) {
				return; // nothing to hear; don't start a stream for it
			}
			if (s == null) {
				s = new Stream(newSource());
				pc.streams.put(stream, s);
			}
			int ms = mono.length * 1000 / Math.max(1, rate);
			if (s.queuedMs + ms > MAX_MS) {
				return; // fell behind; drop rather than lag further
			}
			int buf = AL10.alGenBuffers();
			ShortBuffer data = BufferUtils.createShortBuffer(mono.length);
			data.put(mono).flip();
			AL10.alBufferData(buf, AL10.AL_FORMAT_MONO16, data, rate);
			AL10.alSourceQueueBuffers(s.source, buf);
			s.queued.add(new int[] { buf, ms });
			s.queuedMs += ms;
			s.lastData = System.currentTimeMillis();
		} catch (Throwable t) {
			fail(t);
		}
	}

	private static int newSource() {
		int src = AL10.alGenSources();
		AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
		AL10.alSourcef(src, AL10.AL_ROLLOFF_FACTOR, 1f);
		AL10.alSourcef(src, AL10.AL_REFERENCE_DISTANCE, 0f);
		AL10.alSourcef(src, AL10.AL_MAX_DISTANCE, RANGE);
		try {
			// Linear fade to silence at RANGE, as Minecraft does for its own sounds.
			AL10.alSourcei(src, 0xD000 /* AL_DISTANCE_MODEL */, 0xD003 /* AL_LINEAR_DISTANCE */);
		} catch (Throwable t) {
			// Per-source distance models need an extension Minecraft turns on; without it the default applies.
		}
		return src;
	}

	/** Every rendered frame: feed the sources, start/stop them, follow the PCs and the volume slider. */
	public static void tick() {
		if (broken) {
			incoming.clear();
			return;
		}
		Incoming in;
		while ((in = incoming.poll()) != null) {
			play(in.owner, in.entityId, in.stream, in.rate, in.mono, false);
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		try {
			float gain = mc.options.getSoundVolume(SoundCategory.MASTER) * mc.options.getSoundVolume(SoundCategory.RECORDS);
			long now = System.currentTimeMillis();
			for (Iterator<Map.Entry<UUID, Pc>> it = pcs.entrySet().iterator(); it.hasNext();) {
				Pc pc = it.next().getValue();
				Entity e = mc.world == null ? null : mc.world.getEntityById(pc.entityId);
				for (Iterator<Stream> si = pc.streams.values().iterator(); si.hasNext();) {
					Stream s = si.next();
					if (!AL10.alIsSource(s.source)) {
						si.remove(); // Minecraft restarted its sound system (F3+T, new device)
						continue;
					}
					int done = AL10.alGetSourcei(s.source, AL10.AL_BUFFERS_PROCESSED);
					for (int i = 0; i < done; i++) {
						int b = AL10.alSourceUnqueueBuffers(s.source);
						int[] q = s.queued.poll();
						if (q != null) {
							s.queuedMs -= q[1];
						}
						AL10.alDeleteBuffers(b);
					}
					if (e != null) {
						AL10.alSource3f(s.source, AL10.AL_POSITION, (float) e.getX(), (float) e.getY() + 0.5f, (float) e.getZ());
						AL10.alSourcef(s.source, AL10.AL_GAIN, gain);
					} else {
						AL10.alSourcef(s.source, AL10.AL_GAIN, 0f); // PC not loaded here
					}
					int state = AL10.alGetSourcei(s.source, AL10.AL_SOURCE_STATE);
					if (state != AL10.AL_PLAYING && s.queuedMs >= START_MS) {
						AL10.alSourcePlay(s.source);
					}
					if (s.queued.isEmpty() && now - s.lastData > 5000) {
						AL10.alSourceStop(s.source);
						AL10.alDeleteSources(s.source);
						si.remove();
					}
				}
				if (pc.streams.isEmpty()) {
					it.remove();
				}
			}
		} catch (Throwable t) {
			fail(t);
		}
	}

	/** A PC was switched off or its owner left. */
	public static void stop(UUID owner) {
		Pc pc = pcs.remove(owner);
		if (pc == null || broken) {
			return;
		}
		try {
			for (Stream s : pc.streams.values()) {
				release(s);
			}
		} catch (Throwable t) {
			fail(t);
		}
	}

	public static void stopAll() {
		for (UUID u : pcs.keySet().toArray(new UUID[0])) {
			stop(u);
		}
		incoming.clear();
	}

	private static void release(Stream s) {
		AL10.alSourceStop(s.source);
		int n = AL10.alGetSourcei(s.source, AL10.AL_BUFFERS_QUEUED);
		for (int i = 0; i < n; i++) {
			AL10.alDeleteBuffers(AL10.alSourceUnqueueBuffers(s.source));
		}
		AL10.alDeleteSources(s.source);
	}

	private static void fail(Throwable t) {
		// No sound device, or Minecraft's sound system was restarted under us. Forget
		// our sources; they're gone with the old context anyway.
		System.err.println("[VM Computers] VM sound stopped: " + t);
		pcs.clear();
		incoming.clear();
		broken = !(t instanceof RuntimeException) || String.valueOf(t.getMessage()).contains("No ALCapabilities");
	}
}
