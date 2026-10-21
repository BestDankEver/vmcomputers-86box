package mcvmcomputers.networking;

import net.minecraft.util.Identifier;

public class PacketList {
	// Client to server
	public static final Identifier C2S_ORDER = new Identifier("mcvmcomputers", "c2s_order");
	/** One chunk of an encoded screen frame from a VM owner. */
	public static final Identifier C2S_SCREEN = new Identifier("mcvmcomputers", "c2s_screen_v2");
	public static final Identifier C2S_CHANGE_HDD = new Identifier("mcvmcomputers", "c2s_change_hdd");
	public static final Identifier C2S_TURN_ON_PC = new Identifier("mcvmcomputers", "c2s_turn_on_pc");
	public static final Identifier C2S_TURN_OFF_PC = new Identifier("mcvmcomputers", "c2s_turn_off_pc");
	/** Install the part in an inventory slot into a PC. */
	public static final Identifier C2S_INSTALL_PART = new Identifier("mcvmcomputers", "c2s_install_part");
	/** Take a part out of a PC. */
	public static final Identifier C2S_REMOVE_PART = new Identifier("mcvmcomputers", "c2s_remove_part");
	/** Remember which floppy/CD image is in which drive. */
	public static final Identifier C2S_SET_MEDIA = new Identifier("mcvmcomputers", "c2s_set_media");
	/** New settings for an installed part: entity id, slot, index, new display name, then key/value pairs. */
	public static final Identifier C2S_CONFIGURE_PART = new Identifier("mcvmcomputers", "c2s_configure_part");

	// Server to client
	public static final Identifier S2C_SCREEN = new Identifier("mcvmcomputers", "s2c_screen_v2");
	public static final Identifier S2C_STOP_SCREEN = new Identifier("mcvmcomputers", "s2c_stop_screen");
	/** VM sound for nearby players. C2S: stream, ADPCM packet. S2C: owner, PC entity id, stream, ADPCM packet. */
	public static final Identifier C2S_AUDIO = new Identifier("mcvmcomputers", "c2s_audio");
	public static final Identifier S2C_AUDIO = new Identifier("mcvmcomputers", "s2c_audio");
	public static final Identifier S2C_SYNC_ORDER = new Identifier("mcvmcomputers", "s2c_sync_order");

	/** Part slots for C2S_REMOVE_PART. */
	public static final int SLOT_MOTHERBOARD = 0;
	public static final int SLOT_CPU = 1;
	public static final int SLOT_RAM = 2;
	public static final int SLOT_CARD = 3;
	public static final int SLOT_FLOPPY = 4;
	public static final int SLOT_CDROM = 5;
	public static final int SLOT_HDD = 6;
	/** C2S_CONFIGURE_PART only: name = device id ("" = automatic), options = its settings. */
	public static final int SLOT_KEYBOARD = 7;
	public static final int SLOT_MOUSE = 8;

	/** Media kinds for C2S_SET_MEDIA. */
	public static final int MEDIA_FLOPPY = 0;
	public static final int MEDIA_CD = 1;

	/** Largest custom payload a client may send in 1.16 is 32767 bytes; stay well under it. */
	public static final int MAX_SCREEN_CHUNK = 30000;
	public static final int MAX_AUDIO_PACKET = 16384;
}
