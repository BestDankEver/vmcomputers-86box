package mcvmcomputers.client.entities.render;

import java.util.UUID;

import com.mojang.blaze3d.systems.RenderSystem;

import mcvmcomputers.client.ClientMod;
import mcvmcomputers.entities.EntityWallTV;
import mcvmcomputers.item.ItemList;
import mcvmcomputers.utils.MVCUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

public class WallTVRender extends EntityRenderer<EntityWallTV>{
	
	public WallTVRender(EntityRendererFactory.Context ctx) {
		super(ctx);
	}

	@Override
	public Identifier getTexture(EntityWallTV entity) {
		return null;
	}
	
	@Override
	public void render(EntityWallTV entity, float yaw, float tickDelta, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		if(entity.getOwnerUUID().isEmpty()) {
			return;
		}
		
		matrices.push();
		matrices.translate(0, 0.5, 0);
		Quaternionf look = MVCUtils.lookAt(entity.getPos(), entity.getLookAtPos());
		matrices.multiply(look);
		matrices.push();
		matrices.translate(0, 0, -0.1);
		MinecraftClient.getInstance().getItemRenderer().renderItem(new ItemStack(ItemList.ITEM_WALLTV), ModelTransformationMode.NONE, light, OverlayTexture.DEFAULT_UV, matrices, vertexConsumers, entity.getWorld(), entity.getId());
		matrices.pop();
		if(!entity.getOwnerUUID().isEmpty() && ClientMod.vmScreenTextures.containsKey(UUID.fromString(entity.getOwnerUUID()))) {
			matrices.push();
			matrices.scale(0.0198f, 0.014f, 0.006f);
			matrices.multiply(mcvmcomputers.utils.MVCUtils.euler(0, 0, 180));
			matrices.translate(-63.1f, -45.7f, -24.4f);
			matrices.scale(0.736f, 0.597f, 1f);
			matrices.translate(22, 1.6f, 7.6f);
			Matrix4f matrix4f = matrices.peek().getPositionMatrix();
			VertexConsumer vertexConsumer = vertexConsumers.getBuffer(RenderLayer.getText(ClientMod.vmScreenTextures.get(UUID.fromString(entity.getOwnerUUID()))));
			vertexConsumer.vertex(matrix4f, 0.0F, 128.0F, -0.01F).color(255, 255, 255, 255).texture(0.0F, 1.0F).light(0xF000F0).next();
	        vertexConsumer.vertex(matrix4f, 128.0F, 128.0F, -0.01F).color(255, 255, 255, 255).texture(1.0F, 1.0F).light(0xF000F0).next();
	        vertexConsumer.vertex(matrix4f, 128.0F, 0.0F, -0.01F).color(255, 255, 255, 255).texture(1.0F, 0.0F).light(0xF000F0).next();
	        vertexConsumer.vertex(matrix4f, 0.0F, 0.0F, -0.01F).color(255, 255, 255, 255).texture(0.0F, 0.0F).light(0xF000F0).next();
			matrices.pop();
		}
		matrices.pop();
	}

}
