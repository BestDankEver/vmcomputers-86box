package mcvmcomputers.client.gui;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Makes buttons. On 1.16 this drew wide buttons itself, because the button
 * texture there was 200 pixels wide and wider buttons split in two; 1.20's
 * buttons stretch to any width, so it's just the builder now.
 */
public final class WideButton {
	private WideButton() {
	}

	public static ButtonWidget of(int x, int y, int width, int height, Text message, ButtonWidget.PressAction onPress) {
		return ButtonWidget.builder(message, onPress).dimensions(x, y, width, height).build();
	}
}
