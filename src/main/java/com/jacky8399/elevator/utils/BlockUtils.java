package com.jacky8399.elevator.utils;

import com.jacky8399.elevator.Config;
import com.jacky8399.elevator.Elevator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.*;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import org.joml.AxisAngle4d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;

public class BlockUtils {

    public static final Quaternionf NO_ROTATION = new Quaternionf();
    public static final Vector3f DEFAULT_SCALE = new Vector3f(1);

    public static final List<BlockFace> XZ_CARDINALS = List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST);
    public static final List<BlockFace> CARDINALS = List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN);

    @Nullable
    public static Component findAdjacentSigns(Block block) {
        for (BlockFace face : CARDINALS) {
            Block side = block.getRelative(face);
            if (Tag.SIGNS.isTagged(side.getType())) {
                Sign sign = (Sign) side.getState();

                SignSide sign1 = sign.getSide(Side.FRONT);
                // hahahaha
                return LegacyComponentSerializer.legacySection().deserialize(sign1.getLine(0));
            }
        }
        return null;
    }

    public static boolean isDoorLike(BlockData blockData) {
        return blockData instanceof Door || blockData instanceof TrapDoor || blockData instanceof Gate;
    }

    public static void setDoorLikeState(Block block, boolean open) {
        BlockData blockData = block.getBlockData();
        if (!(blockData instanceof Openable openable && blockData instanceof Powerable powerable))
            throw new IllegalArgumentException("Not a door-like block: " + block);
        if (blockData instanceof TrapDoor)
            open = !open; // flip open value for trap doors
        boolean playSound = openable.isOpen() != open;
        openable.setOpen(open);
        powerable.setPowered(false);
        block.setBlockData(blockData, false);

        // only play door sound on bottom half
        if (blockData instanceof Door door && door.getHalf() != Bisected.Half.BOTTOM)
            playSound = false;
        if (playSound)
            block.getWorld().playSound(block.getLocation(), getDoorLikeSound(block.getType(), open), SoundCategory.BLOCKS,
                    1, (float) Math.random() * 0.1f + 0.9f);
    }

    private static final Map<Material, Sound[]> doorLikeSoundCache = new HashMap<>();
    public static Sound getDoorLikeSound(Material material, boolean open) {
        return doorLikeSoundCache.computeIfAbsent(material, key -> {
            BlockData blockData = key.createBlockData();
            // evil sound key manipulation
            // find the material type, which is usually consistent
            // e.g. block.>copper<.break, block.>nether_wood<.break
            String materialType = blockData.getSoundGroup().getBreakSound().getKey().getKey().split("\\.", 3)[1];
            if (materialType.equals("metal"))
                materialType = "iron"; // of course there are exceptions
            String doorType = switch (blockData) {
                case Door ignored -> "door";
                case TrapDoor ignored -> "trapdoor";
                case Gate ignored -> "fence_gate";
                default -> throw new IllegalArgumentException("Not a door-like block: " + blockData);
            };
            // the door sound is:
            // block.<material>_<door>.open/close
            NamespacedKey openSoundKey = NamespacedKey.minecraft("block." + materialType + "_" + doorType + ".open");
            Sound openSound = Registry.SOUNDS.get(openSoundKey);
            NamespacedKey closeSoundKey = NamespacedKey.minecraft("block." + materialType + "_" + doorType + ".close");
            Sound closeSound = Registry.SOUNDS.get(closeSoundKey);
            if (openSound == null) {
                Elevator.LOGGER.warning("Failed to find open sound for " + blockData + "(" + openSoundKey + "), falling back to wooden door");
                openSound = Sound.BLOCK_WOODEN_DOOR_OPEN;
            }
            if (closeSound == null) {
                Elevator.LOGGER.warning("Failed to find close sound for " + blockData + "(" + closeSoundKey + "), falling back to wooden door");
                closeSound = Sound.BLOCK_WOODEN_DOOR_CLOSE;
            }
            return new Sound[] {openSound, closeSound};
        })[open ? 0 : 1];
    }

    public static float getLowestPoint(Block block) {
        VoxelShape shape = block.getCollisionShape();
        float min = 2;
        for (BoundingBox box : shape.getBoundingBoxes()) {
            if (box.getMaxY() < min)
                min = (float) box.getMinY();
        }
        return min;
    }
    public static float getHighestPoint(Block block) {
        VoxelShape shape = block.getCollisionShape();
        float max = 0;
        for (BoundingBox box : shape.getBoundingBoxes()) {
            if (box.getMaxY() > max)
                max = (float) box.getMaxY();
        }
        return max;
    }

    public static int rayTraceVertical(World world, int x, int y, int z, boolean up, int bounds) {
        int modY = up ? 1 : -1;
        while (up ? y <= bounds : y >= bounds) {
            Block block = world.getBlockAt(x, y, z);
            if (block.getType() != Material.AIR) {
                return y - modY;
            }
            y += modY;
        }
        return y;
    }

    public static void dropItems(World world, Location location, BlockState state) {
        PaperUtils.playBlockBreakEffect(world, location, state.getBlockData());
        Collection<ItemStack> drops = PaperUtils.getDrops(world, location, state);
        for (ItemStack drop : drops) {
            world.dropItemNaturally(location, drop);
        }
    }

    public static boolean unloadCatcher(World world, int blockX, int blockZ) {
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            if (Config.debug) {
                Elevator.LOGGER.log(Level.WARNING, "Unloaded block accessed at " + blockX + "," + blockZ, new RuntimeException("Stack trace"));
            }
            return false;
        }
        return true;
    }

    public static void forEachBlock(World world, BoundingBox box, Consumer<Block> consumer) {
        int minX = (int) box.getMinX();
        int minY = (int) box.getMinY();
        int minZ = (int) box.getMinZ();
        int maxX = (int) box.getMaxX();
        int maxY = (int) box.getMaxY();
        int maxZ = (int) box.getMaxZ();

        for (int j = minY; j < maxY; j++) {
            for (int i = minX; i < maxX; i++) {
                for (int k = minZ; k < maxZ; k++) {
                    if (!unloadCatcher(world, i, k))
                        continue;
                    Block block = world.getBlockAt(i, j, k);
                    consumer.accept(block);
                }
            }
        }
    }

    public static Transformation translateBy(Vector3f translation) {
        return new Transformation(translation, NO_ROTATION, DEFAULT_SCALE, NO_ROTATION);
    }

    public static void ensureCleanUp(List<? extends Display> displays, int delay) {
        Elevator.mustCleanup.addAll(displays);
        Bukkit.getScheduler().runTaskLater(Elevator.INSTANCE, () -> {
            for (Display display : displays) {
                display.remove();
                Elevator.mustCleanup.remove(display);
            }
        }, delay);
    }

    public static List<BlockDisplay> createOutline(World world, BoundingBox box, BlockData data, Player player, Color color) {
        Location playerLocation = player.getLocation();
        playerLocation.setYaw(0);
        playerLocation.setPitch(0);

        return List.of(world.spawn(playerLocation, BlockDisplay.class, blockDisplay -> {
            blockDisplay.setVisibleByDefault(false);
            player.showEntity(Elevator.INSTANCE, blockDisplay);

            blockDisplay.setBlock(data);
            var scale = new Vector3f((float) box.getWidthX() - 0.1f, (float) box.getHeight() - 0.1f, (float) box.getWidthZ() - 0.1f);
            Vector3f translation = new Vector3f(
                    (float) (box.getMinX() + 0.05f - playerLocation.getX()),
                    (float) (box.getMinY() + 0.05f - playerLocation.getY()),
                    (float) (box.getMinZ() + 0.05f - playerLocation.getZ())
            );
            blockDisplay.setTransformation(new Transformation(translation, NO_ROTATION, scale, NO_ROTATION));
            blockDisplay.setBrightness(new Display.Brightness(15, 15));
            blockDisplay.setGlowing(true);
            blockDisplay.setGlowColorOverride(color);
        }));
    }

    public static List<BlockDisplay> createLargeOutline(World world, BoundingBox box, Player player, Color color) {
        var list = new ArrayList<BlockDisplay>();

        int widthX = (int) (box.getWidthX() + 0.5f);
        int widthZ = (int) (box.getWidthZ() + 0.5f);
        int height = (int) (box.getHeight() + 0.5f);

        Location bottom = box.getMin().toLocation(world);
        Location top = bottom.clone();
        top.setY(box.getMaxY());

        Vector xDir = new Vector(1, 0, 0);
        Vector yDir = new Vector(0, 1, 0);
        Vector zDir = new Vector(0, 0, 1);
        // bottom rectangle
        list.addAll(createLine(bottom, xDir, widthX, player, color));
        list.addAll(createLine(bottom.clone().add(0, 0, widthZ), xDir, widthX, player, color));
        list.addAll(createLine(bottom, zDir, widthZ, player, color));
        list.addAll(createLine(bottom.clone().add(widthX, 0, 0), zDir, widthZ, player, color));
        // top rectangle
        list.addAll(createLine(top, xDir, widthX, player, color));
        list.addAll(createLine(top.clone().add(0, 0, widthZ), xDir, widthX, player, color));
        list.addAll(createLine(top, zDir, widthZ, player, color));
        list.addAll(createLine(top.clone().add(widthX, 0, 0), zDir, widthZ, player, color));
        // sides
        list.addAll(createLine(bottom, yDir, height, player, color));
        list.addAll(createLine(bottom.clone().add(widthX, 0, 0), yDir, height, player, color));
        list.addAll(createLine(bottom.clone().add(0, 0, widthZ), yDir, height, player, color));
        list.addAll(createLine(bottom.clone().add(widthX, 0, widthZ), yDir, height, player, color));

        return List.copyOf(list);
    }

    private static final int LINE_SEGMENT_MAX_LENGTH = 16;
    private static final Vector LINE_SEGMENT_AXIS = new Vector(1, 0, 0);
    private static final BlockData LINE_SEGMENT_DISPLAY = Material.WHITE_CONCRETE.createBlockData();
    public static List<BlockDisplay> createLine(Location location, Vector direction, float length, Player player, Color color) {
        World world = location.getWorld();
        Vector normalized = direction.clone().normalize();
        Vector cross = LINE_SEGMENT_AXIS.getCrossProduct(normalized);
        Quaternionf leftRotation = new Quaternionf(new AxisAngle4d(
                LINE_SEGMENT_AXIS.angle(normalized),
                cross.getX(), cross.getY(), cross.getZ()
        ));
        Location playerLocation = player.getLocation();
        playerLocation.setYaw(0);
        playerLocation.setPitch(0);
        Location delta = location.clone().subtract(playerLocation);

        List<BlockDisplay> segments = new ArrayList<>((int) Math.ceil(length / LINE_SEGMENT_MAX_LENGTH));
        for (int i = 0; i < length; i += LINE_SEGMENT_MAX_LENGTH) {
            float segmentLength = Math.min(length - i, LINE_SEGMENT_MAX_LENGTH);
            Vector3f translation = new Vector3f(
                    (float) (delta.getX() + normalized.getX() * i),
                    (float) (delta.getY() + normalized.getY() * i),
                    (float) (delta.getZ() + normalized.getZ() * i)
            );
            Transformation transformation = new Transformation(translation, leftRotation, new Vector3f(segmentLength, 0.05f, 0.05f), NO_ROTATION);

            BlockDisplay display = world.spawn(playerLocation, BlockDisplay.class, blockDisplay -> {
                blockDisplay.setVisibleByDefault(false);
                player.showEntity(Elevator.INSTANCE, blockDisplay);
                blockDisplay.setBlock(LINE_SEGMENT_DISPLAY);
                blockDisplay.setTransformation(transformation);
                blockDisplay.setGlowing(true);
                blockDisplay.setGlowColorOverride(color);
                blockDisplay.setPersistent(false);
            });
            segments.add(display);
        }
        return List.copyOf(segments);
    }

}
