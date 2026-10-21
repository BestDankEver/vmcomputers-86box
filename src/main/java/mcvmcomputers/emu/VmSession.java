package mcvmcomputers.emu;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One running 86Box: launched as a separate process with its automation
 * socket on, its window hidden, frames pulled and input pushed over the
 * socket.
 *
 * Thread use: {@link #start()} and {@link #stop()} may be called from any
 * thread. A worker thread owns the socket; the game thread only queues input
 * and reads {@link #latestFrame()}.
 */
public final class VmSession {
	public enum State {
		STARTING, RUNNING, STOPPING, STOPPED, FAILED
	}

	/** A frame from the VM: a PNG, as 86Box encoded it. */
	public static final class Frame {
		public final byte[] png;
		public final int width;
		public final int height;
		public final long serial;

		Frame(byte[] png, int width, int height, long serial) {
			this.png = png;
			this.width = width;
			this.height = height;
			this.serial = serial;
		}
	}

	private final File exe;
	private final File romDir;
	private final File vmDir;
	private final String vmName;
	private volatile int frameIntervalMs;

	private volatile State state = State.STOPPED;
	private volatile String error;
	private Process process;
	private AutomationClient client;
	private Thread worker;
	private volatile boolean stopRequested;

	/** A piece of the VM's sound: one 86Box output stream (main mix, CD, MIDI...), mono. */
	public static final class AudioChunk {
		public final int stream;
		public final int rate;
		public final short[] mono;
		/** True if every sample is zero. */
		public final boolean silent;

		AudioChunk(int stream, int rate, short[] mono, boolean silent) {
			this.stream = stream;
			this.rate = rate;
			this.mono = mono;
			this.silent = silent;
		}
	}

	private static final int AUDIO_INTERVAL_MS = 30;
	private static final int AUDIO_QUEUE_MAX = 300;
	/** 86Box's output streams worth hearing (not the drive-noise ones, which need a sound pack). */
	private static final String[] AUDIO_STREAMS = { "main", "music", "wavetable", "cd", "ym2151", "midi" };
	private final ConcurrentLinkedQueue<AudioChunk> audio = new ConcurrentLinkedQueue<>();
	private volatile boolean audioCaptured;
	/** Per stream: next frame to pull, recording segment, and when to try again after "nothing recorded". */
	private final long[] audioNext = new long[AUDIO_STREAMS.length];
	private final int[] audioSegment = new int[AUDIO_STREAMS.length];
	private final long[] audioRetryAt = new long[AUDIO_STREAMS.length];

	private static String audioCheckedExe = "";
	private static boolean audioCheckedResult;

	/**
	 * Whether this 86Box can hand its sound over the automation socket
	 * ("audio pull", in 86Box since October 2026). Then the mod mutes 86Box's own
	 * output and plays the sound from the PC instead.
	 */
	public static synchronized boolean supportsAudio(File exe) {
		String key = exe.getAbsolutePath() + "|" + exe.lastModified() + "|" + exe.length();
		if (!key.equals(audioCheckedExe)) {
			audioCheckedExe = key;
			audioCheckedResult = fileContains(exe, "audio pull <stream> <from>".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		}
		return audioCheckedResult;
	}

	private static boolean fileContains(File f, byte[] needle) {
		try (java.io.InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(f), 1 << 16)) {
			// Simple streaming search; the needle has no repeated prefix worth a KMP table.
			int matched = 0;
			int b;
			while ((b = in.read()) >= 0) {
				if (b == (needle[matched] & 0xff)) {
					if (++matched == needle.length) {
						return true;
					}
				} else {
					matched = (b == (needle[0] & 0xff)) ? 1 : 0;
				}
			}
		} catch (IOException e) {
			// Treat as unsupported: 86Box keeps playing its own sound.
		}
		return false;
	}

	private final AtomicReference<Frame> frame = new AtomicReference<>();
	private long frameSerial;

	private final ConcurrentLinkedQueue<int[]> keys = new ConcurrentLinkedQueue<>(); // {scancode, down?1:0}
	private final ConcurrentLinkedQueue<String> commands = new ConcurrentLinkedQueue<>();
	private int mouseDx, mouseDy, mouseButtons, sentButtons;
	private final Object mouseLock = new Object();

	/**
	 * @param exe      86Box executable
	 * @param romDir   ROM set folder (the one containing "machines/")
	 * @param vmDir    this PC's folder; 86box.cfg must already be there
	 * @param vmName   unique name, also used to find and hide 86Box's window
	 */
	public VmSession(File exe, File romDir, File vmDir, String vmName, int frameIntervalMs) {
		this.exe = exe;
		this.romDir = romDir;
		this.vmDir = vmDir;
		this.vmName = vmName;
		this.frameIntervalMs = Math.max(10, frameIntervalMs);
	}

	public State state() {
		return state;
	}

	public String error() {
		return error;
	}

	public boolean isActive() {
		State s = state;
		return s == State.STARTING || s == State.RUNNING || s == State.STOPPING;
	}

	/** Whether the sound comes to the mod (this 86Box supports it) rather than out of 86Box itself. */
	public boolean audioCaptured() {
		return audioCaptured;
	}

	/** The next piece of sound, or null. Only filled while {@link #audioCaptured()}. */
	public AudioChunk pollAudio() {
		return audio.poll();
	}

	public Frame latestFrame() {
		return frame.get();
	}

	public void setFrameInterval(int ms) {
		frameIntervalMs = Math.max(10, ms);
	}

	public File vmDir() {
		return vmDir;
	}

	// ---- Input (any thread) ---------------------------------------------------------

	public void key(int scancode, boolean down) {
		if (scancode > 0 && state == State.RUNNING) {
			keys.add(new int[] { scancode, down ? 1 : 0 });
		}
	}

	/** Relative mouse movement in host pixels; buttons: 1 left, 2 right, 4 middle. */
	public void mouse(int dx, int dy, int buttons) {
		synchronized (mouseLock) {
			mouseDx += dx;
			mouseDy += dy;
			mouseButtons = buttons;
		}
	}

	/** Releases modifier keys, for when the player stops controlling the VM. */
	public void releaseModifiers() {
		for (int sc : KeyMap.RELEASE_ON_UNFOCUS) {
			key(sc, false);
		}
		mouse(0, 0, 0);
	}

	/** Queues a raw automation command (media changes, resets). */
	public void command(String line) {
		if (isActive()) {
			commands.add(line);
		}
	}

	public void insertCd(int drive, File iso) {
		command("cd load " + drive + " " + iso.getAbsolutePath());
	}

	public void ejectCd(int drive) {
		command("cd eject " + drive);
	}

	public void insertFloppy(int drive, File image) {
		command("fdd load " + drive + " " + image.getAbsolutePath());
	}

	public void ejectFloppy(int drive) {
		command("fdd eject " + drive);
	}

	public void ctrlAltDel() {
		command("reset cad");
	}

	public void hardReset() {
		command("reset hard");
	}

	// ---- Lifecycle ---------------------------------------------------------------------

	public synchronized void start() {
		if (isActive()) {
			return;
		}
		stopRequested = false;
		error = null;
		state = State.STARTING;
		worker = new Thread(this::run, "86Box " + vmName);
		worker.setDaemon(true);
		worker.start();
	}

	/** Powers off: asks 86Box to exit, then kills it if it doesn't. Returns at once. */
	public void stop() {
		stopRequested = true;
		if (state == State.RUNNING || state == State.STARTING) {
			state = State.STOPPING;
		}
		Thread w = worker;
		if (w != null) {
			w.interrupt();
		}
	}

	/** Blocking stop, for game shutdown. */
	public void stopAndWait(long timeoutMs) {
		stop();
		Thread w = worker;
		if (w != null) {
			try {
				w.join(timeoutMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		Process p = process;
		if (p != null && p.isAlive()) {
			p.destroyForcibly();
		}
	}

	private void run() {
		File tokenFile = new File(vmDir, "automation.token");
		try {
			tokenFile.delete();
			int port = freePort();
			List<String> cmd = new ArrayList<>();
			cmd.add(exe.getAbsolutePath());
			cmd.add("--vmpath");
			cmd.add(vmDir.getAbsolutePath());
			cmd.add("--vmname");
			cmd.add(vmName);
			cmd.add("--rompath");
			cmd.add(romDir.getAbsolutePath());
			cmd.add("--automation");
			cmd.add("tcp:127.0.0.1:" + port);
			cmd.add("--automation-token-file");
			cmd.add(tokenFile.getAbsolutePath());
			cmd.add("--noconfirm");
			cmd.add("--logfile");
			cmd.add(new File(vmDir, "86box.log").getAbsolutePath());

			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.directory(exe.getParentFile());
			pb.redirectErrorStream(true);
			pb.redirectOutput(new File(vmDir, "86box-console.log"));
			if (!WindowHider.isWindows()) {
				// Linux/macOS: no window at all.
				pb.environment().put("QT_QPA_PLATFORM", "offscreen");
			}
			process = pb.start();

			// Wait for 86Box to come up and write its token.
			String token = null;
			long deadline = System.currentTimeMillis() + 30_000;
			while (token == null) {
				if (!process.isAlive()) {
					throw new IOException("86Box exited during startup (exit code " + process.exitValue() + ")"
							+ logHint());
				}
				if (stopRequested) {
					return;
				}
				if (System.currentTimeMillis() > deadline) {
					throw new IOException("86Box didn't open its automation socket in time" + logHint());
				}
				hideWindow();
				if (tokenFile.isFile() && tokenFile.length() > 0) {
					token = new String(Files.readAllBytes(tokenFile.toPath()), StandardCharsets.US_ASCII).trim();
				} else {
					sleep(100);
				}
			}

			client = AutomationClient.connect("127.0.0.1", port, token, 30_000);
			Map<String, Object> status = client.ok("status");
			if (Json.integer(status, "protocol", 0) != 1) {
				throw new IOException("Unknown 86Box automation protocol " + Json.integer(status, "protocol", 0));
			}
			state = State.RUNNING;
			// Record the sound in memory so the game can play it from the PC (86Box's
			// own output is muted in the config when this is available).
			audioCaptured = supportsAudio(exe) && Json.bool(client.command("audio start"), "ok", false);

			long nextFrame = 0;
			long nextAudio = 0;
			long hideUntil = System.currentTimeMillis() + 10_000;
			while (!stopRequested && process.isAlive()) {
				long now = System.currentTimeMillis();
				if (now < hideUntil) {
					hideWindow(); // Qt may show the window a little after the socket opens.
				}
				flushInput();
				String c;
				while ((c = commands.poll()) != null) {
					Map<String, Object> r = client.command(c);
					if (!Json.bool(r, "ok", false)) {
						System.err.println("[VM Computers] 86Box rejected '" + c + "': " + Json.str(r, "error", "?"));
					}
				}
				if (now >= nextFrame) {
					grabFrame();
					nextFrame = now + frameIntervalMs;
				}
				if (audioCaptured && now >= nextAudio) {
					grabAudio();
					nextAudio = now + AUDIO_INTERVAL_MS;
				}
				long next = audioCaptured ? Math.min(nextFrame, nextAudio) : nextFrame;
				sleep(keys.isEmpty() ? Math.min(8, Math.max(1, next - System.currentTimeMillis())) : 0);
			}
			if (!stopRequested) {
				throw new IOException("86Box stopped unexpectedly" + logHint());
			}
		} catch (Exception e) {
			if (!stopRequested) {
				error = e.getMessage();
				state = State.FAILED;
				System.err.println("[VM Computers] " + e);
			}
		} finally {
			shutdownProcess();
			if (state != State.FAILED) {
				state = State.STOPPED;
			}
			frame.set(null);
			audioCaptured = false;
			audio.clear();
			keys.clear();
			commands.clear();
		}
	}

	private void flushInput() throws IOException {
		// Consecutive presses (or releases) go out as one request.
		int[] k;
		StringBuilder batch = null;
		int batchDir = -1;
		while ((k = keys.poll()) != null) {
			if (batch != null && k[1] != batchDir) {
				client.command(batch.toString());
				batch = null;
			}
			if (batch == null) {
				batch = new StringBuilder(k[1] == 1 ? "key down" : "key up");
				batchDir = k[1];
			}
			batch.append(" 0x").append(Integer.toHexString(k[0]));
		}
		if (batch != null) {
			client.command(batch.toString());
		}

		int dx, dy, b;
		synchronized (mouseLock) {
			dx = mouseDx;
			dy = mouseDy;
			b = mouseButtons;
			mouseDx = 0;
			mouseDy = 0;
		}
		if (dx != 0 || dy != 0 || b != sentButtons) {
			client.command("mouse " + dx + " " + dy + " " + b);
			sentButtons = b;
		}
	}

	private void grabAudio() throws IOException {
		long now = System.currentTimeMillis();
		for (int i = 0; i < AUDIO_STREAMS.length; i++) {
			if (now < audioRetryAt[i]) {
				continue;
			}
			Map<String, Object> st = client.command("audio pull " + AUDIO_STREAMS[i] + " " + audioNext[i] + " 48000");
			if (!Json.bool(st, "ok", false)) {
				audioRetryAt[i] = now + 1000; // this stream hasn't made a sound yet
				continue;
			}
			int segment = Json.integer(st, "segment", 0);
			if (segment != audioSegment[i]) {
				// The stream changed (e.g. its sample rate); positions start over.
				audioSegment[i] = segment;
				if (Json.lng(st, "from", 0) != 0 || audioNext[i] != 0) {
					audioNext[i] = 0;
					continue;
				}
			}
			audioNext[i] = Json.lng(st, "next", audioNext[i]);
			byte[] pcm = Base64.getDecoder().decode(Json.str(st, "data", ""));
			int frames = pcm.length / 4;
			if (frames == 0) {
				continue;
			}
			// Positional sound in OpenAL has to be mono.
			short[] mono = new short[frames];
			boolean silent = true;
			for (int f = 0; f < frames; f++) {
				int l = (short) ((pcm[f * 4] & 0xff) | (pcm[f * 4 + 1] << 8));
				int rr = (short) ((pcm[f * 4 + 2] & 0xff) | (pcm[f * 4 + 3] << 8));
				mono[f] = (short) ((l + rr) / 2);
				silent &= mono[f] == 0;
			}
			if (audio.size() < AUDIO_QUEUE_MAX) {
				audio.add(new AudioChunk(i, Json.integer(st, "rate", 48000), mono, silent));
			}
		}
	}

	private void grabFrame() throws IOException {
		Map<String, Object> r = client.command("screenshot");
		if (!Json.bool(r, "ok", false)) {
			return; // no frame yet (no video card, or still starting)
		}
		String b64 = Json.str(r, "png", null);
		if (b64 == null) {
			return;
		}
		byte[] png = Base64.getDecoder().decode(b64);
		frame.set(new Frame(png, Json.integer(r, "w", 0), Json.integer(r, "h", 0), ++frameSerial));
	}

	private void hideWindow() {
		try {
			WindowHider.hide(vmName);
		} catch (Throwable t) {
			// JNA missing: the window stays visible.
		}
	}

	private void shutdownProcess() {
		Process p = process;
		AutomationClient c = client;
		if (c != null) {
			try {
				if (p != null && p.isAlive()) {
					c.setTimeout(2000);
					c.command("quit");
				}
			} catch (IOException e) {
				// 86Box closes the socket while quitting; that's fine.
			}
			c.close();
			client = null;
		}
		if (p != null) {
			long deadline = System.currentTimeMillis() + 5000;
			while (p.isAlive() && System.currentTimeMillis() < deadline) {
				sleep(50);
			}
			if (p.isAlive()) {
				p.destroyForcibly();
			}
		}
		process = null;
	}

	private String logHint() {
		String tail = tail(new File(vmDir, "86box.log"), 6);
		return tail.isEmpty() ? "" : ". Last log lines:\n" + tail;
	}

	private static String tail(File f, int lines) {
		if (!f.isFile()) {
			return "";
		}
		try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
			long len = raf.length();
			int n = (int) Math.min(len, 4096);
			byte[] b = new byte[n];
			raf.seek(len - n);
			raf.readFully(b);
			String[] all = new String(b, StandardCharsets.UTF_8).split("\r?\n");
			StringBuilder out = new StringBuilder();
			for (int i = Math.max(0, all.length - lines); i < all.length; i++) {
				out.append(all[i].trim()).append('\n');
			}
			return out.toString().trim();
		} catch (IOException e) {
			return "";
		}
	}

	private static int freePort() throws IOException {
		try (ServerSocket s = new ServerSocket(0)) {
			s.setReuseAddress(true);
			return s.getLocalPort();
		}
	}

	private static void sleep(long ms) {
		if (ms <= 0) {
			return;
		}
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			// stop() interrupts us; the loop checks stopRequested.
		}
	}
}
