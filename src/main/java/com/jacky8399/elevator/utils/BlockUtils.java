package com.jacky8399.elevator.utils;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.bukkit.util.VoxelShape;

import java.util.Collections;

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
        while (deltaY != 64 && y != bounds) {
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
}
