package mcvmcomputers.stream;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Encoding of VM screens for other players.
 *
 * 86Box already hands out PNGs; for desktops and text those are small and are
 * passed on as they are. Busy screens (photos, games) make big PNGs, so those
 * are re-encoded as JPEG. Frames are then cut into chunks that fit in a
 * client-to-server packet (1.16 limits those to 32767 bytes).
 */
public final class FrameCodec {
	public static final byte PNG = 0;
	public static final byte JPEG = 1;

	/** PNGs up to this size are sent as they are. */
	public static final int PNG_PASSTHROUGH_LIMIT = 64 * 1024;

	private FrameCodec() {
	}

	public static final class Encoded {
		public final byte format;
		public final byte[] data;
		public final int width;
		public final int height;

		public Encoded(byte format, byte[] data, int width, int height) {
			this.format = format;
			this.data = data;
			this.width = width;
			this.height = height;
		}
	}

	public static Encoded encode(byte[] png, int width, int height, float jpegQuality) throws IOException {
		if (png.length <= PNG_PASSTHROUGH_LIMIT) {
			return new Encoded(PNG, png, width, height);
		}
		BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
		if (src == null) {
			throw new IOException("Unreadable frame");
		}
		// JPEG has no alpha; make sure the image is plain RGB.
		BufferedImage rgb = src;
		if (src.getType() != BufferedImage.TYPE_INT_RGB && src.getType() != BufferedImage.TYPE_3BYTE_BGR) {
			rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
			rgb.getGraphics().drawImage(src, 0, 0, null);
		}
		Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
		if (!it.hasNext()) {
			return new Encoded(PNG, png, width, height);
		}
		ImageWriter w = it.next();
		ByteArrayOutputStream out = new ByteArrayOutputStream(png.length / 4);
		try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
			w.setOutput(ios);
			ImageWriteParam p = w.getDefaultWriteParam();
			p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			p.setCompressionQuality(jpegQuality);
			w.write(null, new IIOImage(rgb, null, null), p);
		} finally {
			w.dispose();
		}
		byte[] jpg = out.toByteArray();
		return jpg.length < png.length ? new Encoded(JPEG, jpg, src.getWidth(), src.getHeight())
				: new Encoded(PNG, png, width, height);
	}

	/** Decodes to ARGB pixels (for JPEG frames; PNG frames go straight to Minecraft's image loader). */
	public static int[] decodeArgb(byte[] data, int[] sizeOut) throws IOException {
		BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
		if (img == null) {
			throw new IOException("Unreadable frame");
		}
		int w = img.getWidth();
		int h = img.getHeight();
		sizeOut[0] = w;
		sizeOut[1] = h;
		return img.getRGB(0, 0, w, h, null, 0, w);
	}

	// ---- Chunking -----------------------------------------------------------------------

	/** Header: frame id, format, width, height, chunk index, chunk count, total length, offset. */
	public static final int HEADER = 4 + 1 + 2 + 2 + 2 + 2 + 4 + 4;

	public static List<byte[]> chunks(Encoded f, int frameId, int maxChunk) {
		int payload = maxChunk - HEADER;
		int count = Math.max(1, (f.data.length + payload - 1) / payload);
		List<byte[]> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int off = i * payload;
			int len = Math.min(payload, f.data.length - off);
			ByteBuffer b = ByteBuffer.allocate(HEADER + len);
			b.putInt(frameId);
			b.put(f.format);
			b.putShort((short) f.width);
			b.putShort((short) f.height);
			b.putShort((short) i);
			b.putShort((short) count);
			b.putInt(f.data.length);
			b.putInt(off);
			b.put(f.data, off, len);
			out.add(b.array());
		}
		return out;
	}

	/** Puts chunks of one sender's frames back together. Not thread-safe. */
	public static final class Assembler {
		private static final int MAX_FRAME = 8 * 1024 * 1024;
		private boolean started;
		private int frameId;
		private byte[] data;
		private boolean[] have;
		private int missing;
		private byte format;
		private int width;
		private int height;

		/** Returns a frame once its last chunk arrives, else null. A newer frame replaces an unfinished one. */
		public Encoded add(byte[] chunk) {
			if (chunk.length < HEADER) {
				return null;
			}
			ByteBuffer b = ByteBuffer.wrap(chunk);
			int id = b.getInt();
			byte fmt = b.get();
			int w = b.getShort() & 0xffff;
			int h = b.getShort() & 0xffff;
			int index = b.getShort() & 0xffff;
			int count = b.getShort() & 0xffff;
			int total = b.getInt();
			int off = b.getInt();
			int len = chunk.length - HEADER;
			if (count == 0 || index >= count || total <= 0 || total > MAX_FRAME || off < 0 || len > total
					|| off > total - len) {
				return null;
			}
			if (!started || id != frameId) {
				if (started && id - frameId < 0) {
					return null; // a late chunk of an older frame
				}
				started = true;
				frameId = id;
				data = new byte[total];
				have = new boolean[count];
				missing = count;
				format = fmt;
				width = w;
				height = h;
			}
			if (data == null || have.length != count || data.length != total || have[index]) {
				return null;
			}
			System.arraycopy(chunk, HEADER, data, off, len);
			have[index] = true;
			if (--missing == 0) {
				Encoded done = new Encoded(format, data, width, height);
				data = null;
				return done;
			}
			return null;
		}
	}
}
