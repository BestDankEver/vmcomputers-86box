package mcvmcomputers.emu;

/**
 * GLFW key codes to PC set-1 scancodes, in the form 86Box's automation
 * socket takes them: the make code, plus 0x100 for E0-prefixed keys.
 *
 * GLFW key codes describe physical key positions (named after the US layout),
 * so this works for any keyboard layout: the guest OS applies its own layout.
 *
 * The constants are GLFW's own values, written out so this class does not
 * depend on LWJGL.
 */
public final class KeyMap {
	private KeyMap() {
	}

	private static final int[] TABLE = new int[349];

	static {
		// Printable keys.
		put(32, 0x39); // space
		put(39, 0x28); // '
		put(44, 0x33); // ,
		put(45, 0x0c); // -
		put(46, 0x34); // .
		put(47, 0x35); // /
		put(48, 0x0b); // 0
		int[] digits = { 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a }; // 1-9
		for (int d = 0; d < 9; d++) {
			put(49 + d, digits[d]);
		}
		put(59, 0x27); // ;
		put(61, 0x0d); // =
		int[] letters = { 0x1e, 0x30, 0x2e, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32, 0x31, 0x18,
				0x19, 0x10, 0x13, 0x1f, 0x14, 0x16, 0x2f, 0x11, 0x2d, 0x15, 0x2c }; // A-Z
		for (int l = 0; l < 26; l++) {
			put(65 + l, letters[l]);
		}
		put(91, 0x1a); // [
		put(92, 0x2b); // backslash
		put(93, 0x1b); // ]
		put(96, 0x29); // `
		put(161, 0x56); // WORLD_1: the extra key next to left shift on ISO keyboards
		put(162, 0x56); // WORLD_2

		// Function and control keys.
		put(256, 0x01); // escape
		put(257, 0x1c); // enter
		put(258, 0x0f); // tab
		put(259, 0x0e); // backspace
		put(260, 0x152); // insert
		put(261, 0x153); // delete
		put(262, 0x14d); // right
		put(263, 0x14b); // left
		put(264, 0x150); // down
		put(265, 0x148); // up
		put(266, 0x149); // page up
		put(267, 0x151); // page down
		put(268, 0x147); // home
		put(269, 0x14f); // end
		put(280, 0x3a); // caps lock
		put(281, 0x46); // scroll lock
		put(282, 0x45); // num lock
		put(283, 0x137); // print screen
		int[] f = { 0x3b, 0x3c, 0x3d, 0x3e, 0x3f, 0x40, 0x41, 0x42, 0x43, 0x44, 0x57, 0x58 }; // F1-F12
		for (int k = 0; k < 12; k++) {
			put(290 + k, f[k]);
		}

		// Keypad.
		int[] kp = { 0x52, 0x4f, 0x50, 0x51, 0x4b, 0x4c, 0x4d, 0x47, 0x48, 0x49 }; // KP 0-9
		for (int k = 0; k < 10; k++) {
			put(320 + k, kp[k]);
		}
		put(330, 0x53); // KP .
		put(331, 0x135); // KP /
		put(332, 0x37); // KP *
		put(333, 0x4a); // KP -
		put(334, 0x4e); // KP +
		put(335, 0x11c); // KP enter

		// Modifiers.
		put(340, 0x2a); // left shift
		put(341, 0x1d); // left control
		put(342, 0x38); // left alt
		put(343, 0x15b); // left super (Windows key)
		put(344, 0x36); // right shift
		put(345, 0x11d); // right control
		put(346, 0x138); // right alt
		put(347, 0x15c); // right super
		put(348, 0x15d); // menu
	}

	private static void put(int glfw, int scancode) {
		TABLE[glfw] = scancode;
	}

	/**
	 * The scancode for a GLFW key, or 0 if there is none.
	 *
	 * @param platformScancode the scancode GLFW reports with the key event. On
	 *        Windows that is already a set-1 code (plus 0x100 when extended), so
	 *        it is used for keys the table does not know.
	 */
	public static int scancode(int glfwKey, int platformScancode, boolean windows) {
		if (glfwKey >= 0 && glfwKey < TABLE.length && TABLE[glfwKey] != 0) {
			return TABLE[glfwKey];
		}
		if (windows && platformScancode > 0 && platformScancode < 0x200) {
			return platformScancode;
		}
		return 0;
	}

	/** Releases the modifiers that can get stuck when focus leaves the VM. */
	public static final int[] RELEASE_ON_UNFOCUS = { 0x1d, 0x11d, 0x38, 0x138, 0x2a, 0x36, 0x15b, 0x15c, 0x0e };
}
