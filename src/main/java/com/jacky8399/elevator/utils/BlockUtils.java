package com.jacky8399.elevator.utils;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.*;

public class BlockUtils {
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
}
