package mcvmcomputers.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

/**
 * A button for any width. Minecraft's button texture is 200 pixels wide, so
 * wider vanilla buttons draw as two mismatched halves; this one draws a plain
 * box in the same colours instead.
 */
public class WideButton extends ButtonWidget {
	public WideButton(int x, int y, int width, int height, Text message, PressAction onPress) {
		super(x, y, width, height, message, onPress);
	}

	/** A vanilla button when it fits the texture, this one otherwise. */
	public static ButtonWidget of(int x, int y, int width, int height, Text message, PressAction onPress) {
		return width <= 200 ? new ButtonWidget(x, y, width, height, message, onPress) : new WideButton(x, y, width, height, message, onPress);
	}

	@Override
	public void renderButton(MatrixStack ms, int mouseX, int mouseY, float delta) {
		boolean hover = this.active && this.isHovered();
		int border = !this.active ? 0xff2a2a2a : hover ? 0xffffffff : 0xff000000;
		int fill = !this.active ? 0xff3a3a3a : hover ? 0xff7d8ab8 : 0xff6f6f6f;
		DrawableHelper.fill(ms, x, y, x + width, y + height, border);
		DrawableHelper.fill(ms, x + 1, y + 1, x + width - 1, y + height - 1, fill);
		DrawableHelper.fill(ms, x + 1, y + 1, x + width - 1, y + 2, !this.active ? 0xff4a4a4a : 0xffa0a0a0); // top highlight
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		String s = this.getMessage().getString();
		int color = this.active ? 0xffffff : 0xa0a0a0;
		tr.drawWithShadow(ms, s, x + (width - tr.getWidth(s)) / 2f, y + (height - 8) / 2f, color);
	}
}
