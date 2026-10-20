package mcvmcomputers.client.gui.setup.pages;

import java.io.IOException;

import mcvmcomputers.client.ClientMod;
import mcvmcomputers.client.gui.setup.GuiSetup;
import mcvmcomputers.client.utils.VMSettings;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/**
 * Last setup page: saves the settings and starts checking for the newest
 * 86Box in the background. 86Box itself is only downloaded the first time a
 * PC is switched on, so players who just watch never download it.
 */
public class SetupPageFinish extends SetupPage {
	public SetupPageFinish(GuiSetup setupGui, TextRenderer textRender) {
		super(setupGui, textRender);
	}

	private void finish() {
		VMSettings s = setupGui.settings;
		s.vmComputersDirectory = ClientMod.vhdDirectory.getParentFile().getAbsolutePath();
		s.unfocusKey1 = ClientMod.glfwUnfocusKey1;
		s.unfocusKey2 = ClientMod.glfwUnfocusKey2;
		s.unfocusKey3 = ClientMod.glfwUnfocusKey3;
		s.unfocusKey4 = ClientMod.glfwUnfocusKey4;
		try {
			s.save();
		} catch (IOException e) {
			e.printStackTrace();
		}
		ClientMod.applySettings(s);
		ClientMod.startBackgroundUpdate();
		minecraft.openScreen(new TitleScreen());
	}

	@Override
	public void render(MatrixStack ms, int mouseX, int mouseY, float delta) {
		int y = setupGui.height / 2 - 40;
		for (String line : setupGui.translation("mcvmcomputers.setup.finish_message").split("\n")) {
			this.textRender.draw(ms, line, setupGui.width / 2 - this.textRender.getWidth(line) / 2, y, -1);
			y += 10;
		}
	}

	@Override
	public void init() {
		if (setupGui.startVb) {
			setupGui.startVb = false;
			finish();
			return;
		}
		String label = setupGui.translation("mcvmcomputers.setup.confirmButton");
		int w = textRender.getWidth(label) + 40;
		setupGui.addButton(new ButtonWidget(setupGui.width / 2 - w / 2, setupGui.height - 40, w, 20, new LiteralText(label), b -> finish()));
	}
}
