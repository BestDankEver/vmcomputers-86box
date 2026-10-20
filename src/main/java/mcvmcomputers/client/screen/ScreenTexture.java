package mcvmcomputers.client.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/**
 * The texture a VM screen is drawn with. New frames are copied into the same
 * GPU texture; a new texture is only made when the resolution changes (the
 * original mod registered a fresh texture every frame).
 *
 * Render thread only.
 */
public final class ScreenTexture {
	private NativeImageBackedTexture texture;
	private Identifier id;
	private int width;
	private int height;

	public Identifier id() {
		return id;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** Shows this image. Takes ownership of it (it is closed or kept). */
	public void update(NativeImage image) {
		if (texture != null && image.getWidth() == width && image.getHeight() == height && texture.getImage() != null) {
			texture.getImage().copyFrom(image);
			image.close();
			texture.upload();
			return;
		}
		destroy();
		width = image.getWidth();
		height = image.getHeight();
		texture = new NativeImageBackedTexture(image);
		id = MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("mcvmcomputers_screen", texture);
	}

	public void destroy() {
		if (id != null) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
			id = null;
		}
		if (texture != null) {
			texture.close();
			texture = null;
		}
		width = 0;
		height = 0;
	}
}
