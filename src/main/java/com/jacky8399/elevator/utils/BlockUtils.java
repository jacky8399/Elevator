package com.jacky8399.elevator.utils;

import com.jacky8399.elevator.Elevator;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.*;
import org.joml.AxisAngle4d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class BlockUtils {

    public static final Quaternionf NO_ROTATION = new Quaternionf();
    public static final Vector3f DEFAULT_SCALE = new Vector3f(1);

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

    public static int rayTraceVertical(Location location, boolean up) {
        int modY = up ? 1 : -1;
        World world = location.getWorld();
        int bounds = up ? world.getMaxHeight() : world.getMinHeight() - 1;
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        int deltaY = 0;
        while (deltaY != 200 && y != bounds) {
            Block block = world.getBlockAt(x, y, z);
            if (block.getType() != Material.AIR) {
                return y;
            }
            y += modY;
            deltaY++;
        }
        return y;
    }

    public static BlockVector toVector(Block block) {
        return new BlockVector(block.getX(), block.getY(), block.getZ());
    }

    public static Block fromVector(World world, Vector vector) {
        return world.getBlockAt(vector.getBlockX(), vector.getBlockY(), vector.getBlockZ());
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
                    Block block = world.getBlockAt(i, j, k);

                    consumer.accept(block);
                }
            }
        }
    }

    public static Transformation translateBy(Vector3f translation) {
        return new Transformation(translation, NO_ROTATION, DEFAULT_SCALE, NO_ROTATION);
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

        Elevator.LOGGER.info(list.size() + " lines");

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
            });
            segments.add(display);
        }
        return List.copyOf(segments);
    }

}
