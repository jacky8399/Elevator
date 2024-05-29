package com.jacky8399.elevator.utils;

import com.jacky8399.elevator.Elevator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.*;
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

    public static List<BlockDisplay> createOutline(World world, BoundingBox box, BlockData data, Player player) {
        if (true || box.getVolume() < 2) {
            return List.of(world.spawn(box.getMin().toLocation(world).add(0.05, 0.05, 0.05), BlockDisplay.class, blockDisplay -> {
                blockDisplay.setVisibleByDefault(false);
                player.showEntity(Elevator.INSTANCE, blockDisplay);

                blockDisplay.setBlock(data);
                var scale = new Vector3f((float) box.getWidthX() - 0.1f, (float) box.getHeight() - 0.1f, (float) box.getWidthZ() - 0.1f);
                blockDisplay.setTransformation(new Transformation(new Vector3f(), NO_ROTATION, scale, NO_ROTATION));
                blockDisplay.setBrightness(new Display.Brightness(15, 15));
                blockDisplay.setGlowing(true);
            }));
        }
        var list = new ArrayList<BlockDisplay>();

        int widthX = (int) (box.getWidthX() + 0.5f);
        int widthZ = (int) (box.getWidthZ() + 0.5f);
        int height = (int) (box.getHeight() + 0.5f);
        for (int x = 0; x < widthX; x += 16) {

        }
        return List.copyOf(list);
    }
}
