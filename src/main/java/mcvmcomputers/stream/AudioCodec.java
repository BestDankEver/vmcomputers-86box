package mcvmcomputers.stream;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Sound for other players: mono, half the sample rate, IMA ADPCM (4 bits a
 * sample). About 12 KB/s for 86Box's 48 kHz mix. Every packet starts with the
 * decoder state, so players who walk up later can join in mid-stream.
 */
public final class AudioCodec {
	private static final int[] STEPS = { 7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60,
			66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
			724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
			5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086,
			29794, 32767 };
	private static final int[] INDEX = { -1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8 };

	private AudioCodec() {
	}

	/** Encoder state carried from one packet to the next for one stream. */
	public static final class Encoder {
		int predictor;
		int index;
		/** An odd sample left over from halving the rate. */
		private short carry;
		private boolean hasCarry;

		/**
		 * Encodes a piece of 48 kHz-style mono sound at half the rate.
		 *
		 * @return {@code [rate/2 as int][predictor as short][index as byte][samples as int][nibbles...]}
		 */
		public byte[] encode(short[] mono, int rate) {
			// Halve the rate: average pairs (a cheap low-pass).
			int n = mono.length + (hasCarry ? 1 : 0);
			short[] half = new short[n / 2];
			int src = 0;
			for (int i = 0; i < half.length; i++) {
				int a = (i == 0 && hasCarry) ? carry : mono[src++];
				int b = mono[src++];
				half[i] = (short) ((a + b) / 2);
			}
			hasCarry = src < mono.length;
			if (hasCarry) {
				carry = mono[src];
			}

			ByteArrayOutputStream bytes = new ByteArrayOutputStream(8 + half.length / 2 + 1);
			try (DataOutputStream out = new DataOutputStream(bytes)) {
				out.writeInt(rate / 2);
				out.writeShort(predictor);
				out.writeByte(index);
				out.writeInt(half.length);
				int pending = -1;
				for (short sample : half) {
					int nib = encodeSample(sample);
					if (pending < 0) {
						pending = nib;
					} else {
						out.writeByte(pending | (nib << 4));
						pending = -1;
					}
				}
				if (pending >= 0) {
					out.writeByte(pending);
				}
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return bytes.toByteArray();
		}

		private int encodeSample(int sample) {
			int step = STEPS[index];
			int diff = sample - predictor;
			int nib = 0;
			if (diff < 0) {
				nib = 8;
				diff = -diff;
			}
			int delta = step >> 3;
			if (diff >= step) {
				nib |= 4;
				diff -= step;
				delta += step;
			}
			step >>= 1;
			if (diff >= step) {
				nib |= 2;
				diff -= step;
				delta += step;
			}
			step >>= 1;
			if (diff >= step) {
				nib |= 1;
				delta += step;
			}
			predictor += (nib & 8) != 0 ? -delta : delta;
			predictor = Math.max(-32768, Math.min(32767, predictor));
			index = Math.max(0, Math.min(88, index + INDEX[nib]));
			return nib;
		}
	}

	/** A decoded packet: its sample rate and mono samples. */
	public static final class Decoded {
		public final int rate;
		public final short[] mono;

		Decoded(int rate, short[] mono) {
			this.rate = rate;
			this.mono = mono;
		}
	}

	public static Decoded decode(byte[] data) {
		ByteBuffer b = ByteBuffer.wrap(data);
		int rate = b.getInt();
		int predictor = b.getShort();
		int index = Math.max(0, Math.min(88, b.get()));
		int count = b.getInt();
		if (count < 0 || count > (b.remaining() * 2)) {
			throw new IllegalArgumentException("bad audio packet");
		}
		short[] out = new short[count];
		for (int i = 0; i < count; i++) {
			int nib = (i & 1) == 0 ? (b.get(b.position() + i / 2) & 0x0f) : ((b.get(b.position() + i / 2) >> 4) & 0x0f);
			int step = STEPS[index];
			int delta = step >> 3;
			if ((nib & 4) != 0) {
				delta += step;
			}
			if ((nib & 2) != 0) {
				delta += step >> 1;
			}
			if ((nib & 1) != 0) {
				delta += step >> 2;
			}
			predictor += (nib & 8) != 0 ? -delta : delta;
			predictor = Math.max(-32768, Math.min(32767, predictor));
			index = Math.max(0, Math.min(88, index + INDEX[nib]));
			out[i] = (short) predictor;
		}
		return new Decoded(rate, out);
	}
}
