package mcvmcomputers.entities;

import net.minecraft.text.Text;
import java.util.UUID;

import mcvmcomputers.MainMod;
import mcvmcomputers.client.ClientMod;
import mcvmcomputers.emu.HardwareSpec;
import mcvmcomputers.item.ItemPCCase;
import mcvmcomputers.item.ItemPCCaseSidepanel;
import mcvmcomputers.utils.HardwareNbt;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class EntityPC extends Entity {
	private static final TrackedData<String> OWNER_UUID = DataTracker.registerData(EntityPC.class, TrackedDataHandlerRegistry.STRING);
	/** Stable identity of this computer; names its 86Box folder, so BIOS settings survive moving it. */
	private static final TrackedData<String> PC_ID = DataTracker.registerData(EntityPC.class, TrackedDataHandlerRegistry.STRING);
	private static final TrackedData<NbtCompound> HARDWARE = DataTracker.registerData(EntityPC.class, TrackedDataHandlerRegistry.NBT_COMPOUND);

	private static final TrackedData<Float> LOOK_AT_POS_X = DataTracker.registerData(EntityPC.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Float> LOOK_AT_POS_Y = DataTracker.registerData(EntityPC.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Float> LOOK_AT_POS_Z = DataTracker.registerData(EntityPC.class, TrackedDataHandlerRegistry.FLOAT);
	private static final TrackedData<Boolean> GLASS_SIDEPANEL = DataTracker.registerData(EntityPC.class, TrackedDataHandlerRegistry.BOOLEAN);

	private NbtCompound cachedTag;
	private HardwareSpec cachedSpec;

	public EntityPC(EntityType<?> type, World world) {
		super(type, world);
	}

	public EntityPC(World world, double x, double y, double z) {
		this(EntityList.PC, world);
		this.setPosition(x, y, z);
	}

	/** @param itemTag NBT of the PC item being placed (carries the hardware of a built PC), or null. */
	public EntityPC(World world, double x, double y, double z, Vec3d lookAt, UUID owner, NbtCompound itemTag) {
		this(EntityList.PC, world);
		this.setPosition(x, y, z);
		this.getDataTracker().set(LOOK_AT_POS_X, (float) lookAt.x);
		this.getDataTracker().set(LOOK_AT_POS_Y, (float) lookAt.y);
		this.getDataTracker().set(LOOK_AT_POS_Z, (float) lookAt.z);
		this.getDataTracker().set(OWNER_UUID, owner.toString());
		String id = UUID.randomUUID().toString();
		if (itemTag != null) {
			if (itemTag.contains("Hardware", 10)) {
				this.getDataTracker().set(HARDWARE, itemTag.getCompound("Hardware").copy());
			}
			if (!itemTag.getString("PcId").isEmpty()) {
				id = itemTag.getString("PcId");
			}
		}
		this.getDataTracker().set(PC_ID, id);
	}

	public EntityPC(World world, double x, double y, double z, Vec3d lookAt, UUID owner, boolean glassSidepanel, NbtCompound itemTag) {
		this(world, x, y, z, lookAt, owner, itemTag);
		this.getDataTracker().set(GLASS_SIDEPANEL, glassSidepanel);
	}

	public Vec3d getLookAtPos() {
		return new Vec3d(this.getDataTracker().get(LOOK_AT_POS_X), this.getDataTracker().get(LOOK_AT_POS_Y), this.getDataTracker().get(LOOK_AT_POS_Z));
	}

	@Override
	protected void initDataTracker() {
		this.getDataTracker().startTracking(OWNER_UUID, "");
		this.getDataTracker().startTracking(PC_ID, "");
		this.getDataTracker().startTracking(HARDWARE, new NbtCompound());
		this.getDataTracker().startTracking(LOOK_AT_POS_X, 0f);
		this.getDataTracker().startTracking(LOOK_AT_POS_Y, 0f);
		this.getDataTracker().startTracking(LOOK_AT_POS_Z, 0f);
		this.getDataTracker().startTracking(GLASS_SIDEPANEL, false);
	}

	@Override
	protected void readCustomDataFromNbt(NbtCompound tag) {
		this.getDataTracker().set(LOOK_AT_POS_X, tag.getFloat("LookAtX"));
		this.getDataTracker().set(LOOK_AT_POS_Y, tag.getFloat("LookAtY"));
		this.getDataTracker().set(LOOK_AT_POS_Z, tag.getFloat("LookAtZ"));
		this.getDataTracker().set(OWNER_UUID, tag.getString("Owner"));
		this.getDataTracker().set(GLASS_SIDEPANEL, tag.getBoolean("GlassSidepanel"));
		this.getDataTracker().set(HARDWARE, tag.getCompound("Hardware"));
		String id = tag.getString("PcId");
		this.getDataTracker().set(PC_ID, id.isEmpty() ? UUID.randomUUID().toString() : id);
	}

	@Override
	protected void writeCustomDataToNbt(NbtCompound tag) {
		tag.putFloat("LookAtX", this.getDataTracker().get(LOOK_AT_POS_X));
		tag.putFloat("LookAtY", this.getDataTracker().get(LOOK_AT_POS_Y));
		tag.putFloat("LookAtZ", this.getDataTracker().get(LOOK_AT_POS_Z));
		tag.putString("Owner", this.getDataTracker().get(OWNER_UUID));
		tag.putBoolean("GlassSidepanel", this.getDataTracker().get(GLASS_SIDEPANEL));
		tag.put("Hardware", this.getDataTracker().get(HARDWARE));
		tag.putString("PcId", this.getDataTracker().get(PC_ID));
	}

	public String getOwner() {
		return this.getDataTracker().get(OWNER_UUID);
	}

	public void setOwner(String uid) {
		this.getDataTracker().set(OWNER_UUID, uid);
	}

	public String getPcId() {
		return this.getDataTracker().get(PC_ID);
	}

	public boolean getGlassSidepanel() {
		return this.getDataTracker().get(GLASS_SIDEPANEL);
	}

	public NbtCompound getHardwareTag() {
		return this.getDataTracker().get(HARDWARE);
	}

	/** The installed hardware. Don't modify the result; use {@link #setHardware}. */
	public HardwareSpec getHardware() {
		NbtCompound t = getHardwareTag();
		if (t != cachedTag || cachedSpec == null) {
			cachedSpec = HardwareNbt.read(t);
			cachedTag = t;
		}
		return cachedSpec;
	}

	/** A copy of the installed hardware that can be changed and set back. */
	public HardwareSpec copyHardware() {
		return HardwareNbt.read(getHardwareTag());
	}

	public void setHardware(HardwareSpec spec) {
		this.getDataTracker().set(HARDWARE, HardwareNbt.write(spec));
	}

	public boolean isOwnedBy(PlayerEntity p) {
		return p != null && p.getUuid().toString().equals(getOwner());
	}

	@Override
	public ActionResult interact(PlayerEntity player, Hand hand) {
		if (hand != Hand.MAIN_HAND) {
			return ActionResult.PASS;
		}
		if (!player.getWorld().isClient) {
			if (player.isSneaking() && isOwnedBy(player)) {
				if (MainMod.isRunning(this)) {
					player.sendMessage(Text.translatable("mcvmcomputers.turn_off_first").formatted(Formatting.RED), false);
					return ActionResult.SUCCESS;
				}
				if (this.getGlassSidepanel()) {
					player.getWorld().spawnEntity(new ItemEntity(player.getWorld(), this.getPos().x, this.getPos().y, this.getPos().z,
							ItemPCCaseSidepanel.createPCStackByEntity(this)));
				} else {
					player.getWorld().spawnEntity(new ItemEntity(player.getWorld(), this.getPos().x, this.getPos().y, this.getPos().z,
							ItemPCCase.createPCStackByEntity(this)));
				}
				this.discard();
			}
		} else {
			if (!player.isSneaking()) {
				if (isOwnedBy(player)) {
					ClientMod.currentPC = this;
					MainMod.pcOpenGui.run();
				} else {
					player.sendMessage(Text.translatable("mcvmcomputers.not_your_computer").formatted(Formatting.RED), false);
				}
			}
		}
		return ActionResult.SUCCESS;
	}

	@Override
	public boolean canHit() {
		return true;
	}

	@Override
	public Packet<ClientPlayPacketListener> createSpawnPacket() {
		return new EntitySpawnS2CPacket(this);
	}
}
