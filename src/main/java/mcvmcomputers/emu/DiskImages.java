package mcvmcomputers.emu;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import mcvmcomputers.emu.HardwareSpec.DiskPart;

/**
 * Creates blank hard disk images the way 86Box's New Hard Disk dialog does:
 * raw (.img), fixed-size VHD and dynamic-size VHD. Differencing VHDs are left
 * to 86Box, which makes them on first start from the config's vhd_parent.
 *
 * Also reads an existing VHD's geometry, since 86Box uses the geometry stored
 * in the file rather than the config's.
 */
public final class DiskImages {
	private static final long VHD_EPOCH = 946684800L; // 2000-01-01T00:00:00Z
	private static final int BLOCK = 2 * 1024 * 1024; // 86Box's "large" VHD block size (4096 sectors)

	private DiskImages() {
	}

	/** Disk interfaces a new disk can be made for, with the geometry 86Box would give it. */
	public enum Interface {
		IDE("ide", "IDE / SCSI", 63, 16, 65535), ESDI("esdi", "ESDI", 35, 16, 1024), MFM("mfm", "MFM (17 sectors)", 17, 15, 1024),
		RLL("rll", "RLL (26 sectors)", 26, 15, 1024), XTA("xta", "XTA", 17, 15, 1024);

		public final String id;
		public final String label;
		public final int sectors, heads, maxCylinders;

		Interface(String id, String label, int sectors, int heads, int maxCylinders) {
			this.id = id;
			this.label = label;
			this.sectors = sectors;
			this.heads = heads;
			this.maxCylinders = maxCylinders;
		}

		/** Largest geometry 86Box accepts on this interface: {sectors, heads, cylinders}. */
		public int[] limits() {
			return HardwareSpec.geometryLimits(this == RLL ? "mfm" : id);
		}

		/** {cylinders, heads, sectors} for about {@code sizeMb}, rounded down to whole cylinders. */
		public int[] geometryFor(long sizeMb) {
			long sectorsTotal = sizeMb * 2048L;
			long cyl = Math.max(1, sectorsTotal / ((long) this.sectors * heads));
			return new int[] { (int) Math.min(cyl, maxCylinders), heads, this.sectors };
		}
	}

	public static long bytes(int cylinders, int heads, int sectors) {
		return (long) cylinders * heads * sectors * 512L;
	}

	/** Writes a new image of the disk's format and geometry. Fails if the file exists. */
	public static void create(File f, DiskPart d) throws IOException {
		if (f.exists()) {
			throw new IOException(f.getName() + " already exists");
		}
		File parent = f.getParentFile();
		if (parent != null) {
			parent.mkdirs();
		}
		long size = bytes(d.cylinders, d.heads, d.sectors);
		try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
			switch (d.format) {
			case DiskPart.VHD_FIXED:
				raf.setLength(size + 512);
				raf.seek(size);
				raf.write(footer(size, d.cylinders, d.heads, d.sectors, 2, -1L));
				break;
			case DiskPart.VHD_DYNAMIC: {
				int entries = (int) ((size + BLOCK - 1) / BLOCK);
				long batBytes = ((entries * 4L + 511) / 512) * 512;
				byte[] foot = footer(size, d.cylinders, d.heads, d.sectors, 3, 512L);
				raf.write(foot);
				raf.write(dynamicHeader(1536L, entries));
				byte[] bat = new byte[(int) batBytes];
				Arrays.fill(bat, (byte) 0xff);
				raf.write(bat);
				raf.write(foot);
				break;
			}
			default:
				raf.setLength(size);
			}
		} catch (IOException e) {
			f.delete();
			throw e;
		}
	}

	private static byte[] footer(long size, int cyl, int heads, int spt, int type, long dataOffset) {
		ByteBuffer b = ByteBuffer.allocate(512); // big-endian
		b.put("conectix".getBytes(StandardCharsets.US_ASCII));
		b.putInt(2); // features: reserved bit
		b.putInt(0x00010000); // format version
		b.putLong(dataOffset); // fixed disks: none
		b.putInt((int) (System.currentTimeMillis() / 1000L - VHD_EPOCH));
		b.put("86Bx".getBytes(StandardCharsets.US_ASCII)); // creator application
		b.putInt(0x00010000); // creator version
		b.put("Wi2k".getBytes(StandardCharsets.US_ASCII)); // creator host OS
		b.putLong(size); // original size
		b.putLong(size); // current size
		b.putShort((short) Math.min(cyl, 65535));
		b.put((byte) heads);
		b.put((byte) spt);
		b.putInt(type); // 2 = fixed, 3 = dynamic
		int checksumAt = b.position();
		b.putInt(0);
		UUID u = UUID.randomUUID();
		b.putLong(u.getMostSignificantBits());
		b.putLong(u.getLeastSignificantBits());
		byte[] out = b.array();
		ByteBuffer.wrap(out).putInt(checksumAt, checksum(out));
		return out;
	}

	private static byte[] dynamicHeader(long tableOffset, int entries) {
		ByteBuffer b = ByteBuffer.allocate(1024);
		b.put("cxsparse".getBytes(StandardCharsets.US_ASCII));
		b.putLong(-1L); // data offset: unused
		b.putLong(tableOffset);
		b.putInt(0x00010000); // header version
		b.putInt(entries);
		b.putInt(BLOCK);
		int checksumAt = b.position();
		b.putInt(0);
		byte[] out = b.array(); // parent fields stay zero
		ByteBuffer.wrap(out).putInt(checksumAt, checksum(out));
		return out;
	}

	private static int checksum(byte[] data) {
		int sum = 0;
		for (byte x : data) {
			sum += x & 0xff;
		}
		return ~sum;
	}

	/**
	 * A VHD's {cylinders, heads, sectors, size in MB}, or null if the file isn't
	 * a VHD. 86Box takes the geometry from the file.
	 */
	public static int[] readVhd(File f) {
		try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
			if (raf.length() < 512) {
				return null;
			}
			byte[] foot = new byte[512];
			raf.seek(raf.length() - 512);
			raf.readFully(foot);
			if (!new String(foot, 0, 8, StandardCharsets.US_ASCII).equals("conectix")) {
				raf.seek(0); // some tools only write the copy at the start
				raf.readFully(foot);
				if (!new String(foot, 0, 8, StandardCharsets.US_ASCII).equals("conectix")) {
					return null;
				}
			}
			ByteBuffer b = ByteBuffer.wrap(foot);
			long current = b.getLong(48);
			int cyl = b.getShort(56) & 0xffff;
			int heads = foot[58] & 0xff;
			int spt = foot[59] & 0xff;
			return new int[] { cyl, heads, spt, (int) Math.max(1, current / (1024 * 1024)) };
		} catch (IOException e) {
			return null;
		}
	}
}
