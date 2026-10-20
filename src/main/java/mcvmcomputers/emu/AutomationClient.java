package mcvmcomputers.emu;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Client for 86Box's automation socket (protocol version 1, see
 * doc/specifications/automation.md in the 86Box source).
 *
 * One request at a time: a line out, one JSON object back.
 *
 * About latency: 86Box sends each reply as two writes, the JSON and then a
 * lone newline. With Nagle's algorithm on 86Box's side and delayed ACKs on
 * ours, the newline (and the next reply after it) can sit for 40 ms on Linux
 * or up to 200 ms on Windows. Two things avoid that here: replies are parsed
 * as soon as the JSON object is complete, without waiting for the newline,
 * and a single space is sent right after each reply so the ACK goes out at
 * once. 86Box skips leading spaces in a request line, so the space is
 * harmless. Measured: about 1 ms per simple request instead of about 46 ms.
 */
public final class AutomationClient implements Closeable {
	private final Socket socket;
	private final InputStream in;
	private final OutputStream out;
	private final byte[] buf = new byte[1 << 16];
	private int bufLen = 0;
	private final ByteArrayOutputStream reply = new ByteArrayOutputStream(1 << 16);

	private AutomationClient(Socket socket) throws IOException {
		this.socket = socket;
		this.in = socket.getInputStream();
		this.out = socket.getOutputStream();
	}

	/** Connects and authenticates. {@code token} may be null for an insecure endpoint. */
	public static AutomationClient connect(String host, int port, String token, int timeoutMs) throws IOException {
		Socket s = new Socket();
		s.setTcpNoDelay(true);
		s.connect(new InetSocketAddress(host, port), timeoutMs);
		s.setSoTimeout(timeoutMs);
		AutomationClient c = new AutomationClient(s);
		if (token != null) {
			Map<String, Object> r = c.command("auth " + token);
			if (!Json.bool(r, "ok", false)) {
				c.close();
				throw new IOException("86Box refused the automation token: " + Json.str(r, "error", "?"));
			}
		}
		return c;
	}

	public void setTimeout(int ms) throws IOException {
		socket.setSoTimeout(ms);
	}

	/** Sends one request and returns the parsed reply. Throws if the connection is gone. */
	public synchronized Map<String, Object> command(String line) throws IOException {
		if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
			throw new IllegalArgumentException("Request must be a single line");
		}
		out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
		out.flush();
		String json = readObject();
		out.write(' '); // ACK nudge, see the class comment.
		out.flush();
		return Json.parseObject(json);
	}

	/** Like {@link #command} but throws when 86Box answers {"ok": false}. */
	public Map<String, Object> ok(String line) throws IOException {
		Map<String, Object> r = command(line);
		if (!Json.bool(r, "ok", false)) {
			throw new IOException("86Box: " + line.split(" ")[0] + ": " + Json.str(r, "error", "failed"));
		}
		return r;
	}

	/** Reads one complete JSON object, skipping whitespace between replies. */
	private String readObject() throws IOException {
		reply.reset();
		int depth = 0;
		boolean inString = false;
		boolean escape = false;
		boolean started = false;
		int pos = 0;
		while (true) {
			if (pos >= bufLen) {
				int n = in.read(buf, 0, buf.length);
				if (n < 0) {
					throw new IOException("86Box closed the automation connection");
				}
				bufLen = n;
				pos = 0;
			}
			int chunkStart = pos;
			for (; pos < bufLen; pos++) {
				byte b = buf[pos];
				if (!started) {
					if (b == '{') {
						started = true;
						depth = 1;
						chunkStart = pos;
					}
					continue; // skip newlines left over from the previous reply
				}
				if (inString) {
					if (escape) {
						escape = false;
					} else if (b == '\\') {
						escape = true;
					} else if (b == '"') {
						inString = false;
					}
				} else if (b == '"') {
					inString = true;
				} else if (b == '{') {
					depth++;
				} else if (b == '}') {
					depth--;
					if (depth == 0) {
						reply.write(buf, chunkStart, pos + 1 - chunkStart);
						// Keep whatever follows (normally the newline) for next time.
						int rest = bufLen - (pos + 1);
						System.arraycopy(buf, pos + 1, buf, 0, rest);
						bufLen = rest;
						return new String(reply.toByteArray(), StandardCharsets.UTF_8);
					}
				}
			}
			if (started) {
				reply.write(buf, chunkStart, bufLen - chunkStart);
			}
			bufLen = 0;
			pos = 0;
		}
	}

	public boolean isOpen() {
		return !socket.isClosed();
	}

	@Override
	public void close() {
		try {
			socket.close();
		} catch (IOException e) {
			// Already gone.
		}
	}
}
