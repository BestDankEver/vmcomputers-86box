package mcvmcomputers.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import java.nio.DoubleBuffer;
import java.util.ArrayList;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;

import mcvmcomputers.client.ClientMod;
import mcvmcomputers.emu.VmSession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Language;

/**
 * While this screen is open, keyboard and mouse go to the VM. The unfocus key
 * combination (Ctrl+Backspace by default) gives control back.
 */
public class GuiFocus extends Screen {
	private String keyString;
	private ArrayList<Integer> keys;
	private final Language lang = Language.getInstance();
	private final MinecraftClient minecraft = MinecraftClient.getInstance();
	private double lastX = Double.NaN;
	private double lastY = Double.NaN;
	/**
	 * Buttons already down when focus started (the right-click on the monitor
	 * that opened this screen). They're not sent until released, which is
	 * what made clicks show up as right-clicks in the original mod.
	 */
	private int heldAtStart = -1;

	public GuiFocus() {
		super(Text.translatable("Focus"));
	}

	@Override
	protected void init() {
		keyString = "";
		keys = new ArrayList<>();
		for (int key : new int[] { ClientMod.glfwUnfocusKey1, ClientMod.glfwUnfocusKey2, ClientMod.glfwUnfocusKey3, ClientMod.glfwUnfocusKey4 }) {
			if (key > 0) {
				keys.add(key);
			}
		}
		boolean plus = false;
		for (int key : keys) {
			if (plus) {
				keyString += " + ";
			}
			keyString += ClientMod.getKeyName(key);
			plus = true;
		}
		GLFW.glfwSetInputMode(minecraft.getWindow().getHandle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
		lastX = Double.NaN;
		lastY = Double.NaN;
		heldAtStart = -1;
	}

	@Override
	public void render(DrawContext ms, int wmouseX, int wmouseY, float delta) {
		long window = minecraft.getWindow().getHandle();
		VmSession vm = ClientMod.vm;

		// Relative mouse movement and buttons.
		DoubleBuffer mx = BufferUtils.createDoubleBuffer(1);
		DoubleBuffer my = BufferUtils.createDoubleBuffer(1);
		GLFW.glfwGetCursorPos(window, mx, my);
		double x = mx.get(0);
		double y = my.get(0);
		int buttons = (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS ? 1 : 0)
				| (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS ? 2 : 0)
				| (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS ? 4 : 0);
		if (heldAtStart < 0) {
			heldAtStart = buttons;
		}
		heldAtStart &= buttons; // forget a button once it's released
		buttons &= ~heldAtStart;
		if (vm != null) {
			int dx = Double.isNaN(lastX) ? 0 : (int) Math.round(x - lastX);
			int dy = Double.isNaN(lastY) ? 0 : (int) Math.round(y - lastY);
			vm.mouse(dx, dy, buttons);
		}
		lastX = x;
		lastY = y;

		ms.drawText(this.textRenderer, lang.get("mcvmcomputers.focus.lose").replace("%s", keyString), 4, 4, -1, false);
		if (vm == null || vm.state() != VmSession.State.RUNNING) {
			ms.drawText(this.textRenderer, lang.get("mcvmcomputers.focus.not_running"), 4, 14, 0xffff5555, false);
		}

		boolean pressed = !keys.isEmpty();
		for (int key : keys) {
			if (GLFW.glfwGetKey(window, key) == GLFW.GLFW_RELEASE) {
				pressed = false;
				break;
			}
		}
		if (pressed) {
			minecraft.setScreen(null);
		}
		super.render(ms, wmouseX, wmouseY, delta);
	}

	@Override
	public void removed() {
		VmSession vm = ClientMod.vm;
		if (vm != null) {
			vm.releaseModifiers();
		}
		GLFW.glfwSetInputMode(minecraft.getWindow().getHandle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
