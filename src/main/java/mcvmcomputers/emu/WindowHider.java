package mcvmcomputers.emu;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Hides 86Box's own window on Windows, so the VM only shows up on in-game
 * monitors.
 *
 * 86Box titles its window "&lt;VM name&gt; - 86Box &lt;version&gt;", and each
 * VM is started with a unique name, so windows are matched by that prefix.
 *
 * Uses JNA, which Minecraft ships. Every entry point is guarded: if JNA or
 * user32 is unavailable the window simply stays visible.
 */
public final class WindowHider {
	private WindowHider() {
	}

	private interface User32 extends StdCallLibrary {
		interface WndEnumProc extends StdCallCallback {
			boolean callback(Pointer hWnd, Pointer data);
		}

		boolean EnumWindows(WndEnumProc proc, Pointer data);

		int GetWindowTextW(Pointer hWnd, char[] text, int max);

		boolean IsWindowVisible(Pointer hWnd);

		boolean ShowWindow(Pointer hWnd, int cmd);
	}

	private static final int SW_HIDE = 0;
	private static User32 user32;
	private static boolean failed;

	private static synchronized User32 lib() {
		if (user32 == null && !failed) {
			try {
				user32 = (User32) Native.loadLibrary("user32", User32.class);
			} catch (Throwable t) {
				failed = true;
			}
		}
		return user32;
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
	}

	/**
	 * Hides every visible top-level window whose title starts with
	 * "&lt;vmName&gt; - ". Returns how many were hidden.
	 */
	public static int hide(String vmName) {
		if (!isWindows()) {
			return 0;
		}
		final User32 u;
		try {
			u = lib();
		} catch (Throwable t) {
			return 0;
		}
		if (u == null) {
			return 0;
		}
		final String prefix = vmName + " - ";
		final int[] count = { 0 };
		try {
			u.EnumWindows((hWnd, data) -> {
				if (u.IsWindowVisible(hWnd)) {
					char[] buf = new char[512];
					int n = u.GetWindowTextW(hWnd, buf, buf.length);
					if (n > 0 && new String(buf, 0, n).startsWith(prefix)) {
						u.ShowWindow(hWnd, SW_HIDE);
						count[0]++;
					}
				}
				return true;
			}, null);
		} catch (Throwable t) {
			return 0;
		}
		return count[0];
	}
}
