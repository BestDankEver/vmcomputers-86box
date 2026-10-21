package mcvmcomputers.client.gui.setup.pages;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import java.io.File;

import mcvmcomputers.client.gui.setup.GuiSetup;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;

public class SetupPageIntroMessage extends SetupPage{
	public SetupPageIntroMessage(GuiSetup setupGui, TextRenderer textRender) {
		super(setupGui, textRender);
	}

	@Override
	public void render(DrawContext ms, int mouseX, int mouseY, float delta) {
		if(!setupGui.loadedConfiguration) {
			String text = setupGui.translation("mcvmcomputers.setup.intro_message");
			
			int offY = -36;
			
			for(String s : text.split("\n")) {
				ms.drawText(this.textRender, s, (int) (setupGui.width/2 - this.textRender.getWidth(s)/2), (int) (setupGui.height/2 + offY), -1, false);
				offY+=10;
			}
		}
	}

	@Override
	public void init() {
		if(!setupGui.loadedConfiguration) {
			int buttonW = textRender.getWidth(setupGui.translation("mcvmcomputers.setup.nextButton"))+20;
			setupGui.addButton(mcvmcomputers.client.gui.WideButton.of(setupGui.width/2 - (buttonW/2), setupGui.height - 40, buttonW, 20, Text.literal(setupGui.translation("mcvmcomputers.setup.nextButton")), (bw) -> this.setupGui.nextPage()));
		}else {
			int useConfigW = textRender.getWidth(setupGui.translation("mcvmcomputers.setup.useConfig"))+20;
			int redoSetupW = textRender.getWidth(setupGui.translation("mcvmcomputers.setup.redoSetup"))+20;
			int w = useConfigW;
			if(redoSetupW > useConfigW)
				w = redoSetupW;
			setupGui.addButton(mcvmcomputers.client.gui.WideButton.of(setupGui.width/2 - (w/2), setupGui.height / 2 - 25, w, 20, Text.literal(setupGui.translation("mcvmcomputers.setup.useConfig")), (bw) -> this.setupGui.lastPage()));
			setupGui.addButton(mcvmcomputers.client.gui.WideButton.of(setupGui.width/2 - (w/2), setupGui.height / 2 + 5, w, 20, Text.literal(setupGui.translation("mcvmcomputers.setup.redoSetup")), (bw) -> this.delete()));
		}
	}
	public void delete() {
		setupGui.loadedConfiguration = false;
		File f = new File(minecraft.runDirectory, "vm_computers/setup.json");
		if(f.exists()) {
			f.delete();
		}
		this.setupGui.nextPage();
	}
}
