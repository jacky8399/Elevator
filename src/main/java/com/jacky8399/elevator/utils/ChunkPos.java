package com.jacky8399.elevator.utils;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

import java.util.StringJoiner;

public class ChunkPos {
    public static long getChunkKey(Chunk chunk) {
        return getChunkKey(chunk.getX(), chunk.getZ());
    }

    public static long getChunkKey(int x, int z) {
        return (long) x & 0xffffffffL | ((long) z & 0xffffffffL) << 32;
    }

    public static boolean isLoaded(World world, long chunkKey) {
        return world.isChunkLoaded((int) chunkKey, (int) (chunkKey >> 32));
    }

    public static int getX(long chunkKey) {
        return (int) chunkKey;
    }

    public static int getZ(long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    public static String toString(long chunkKey) {
        return "(" + ((int) chunkKey) + "," + ((int) (chunkKey >> 32)) + ")";
    }

    public static String toString(long[] chunkKeys) {
        StringJoiner joiner = new StringJoiner(", ");
        for (long chunkKey : chunkKeys) {
            joiner.add(toString(chunkKey));
        }
        return joiner.toString();
    }

    public static long[] fromBoundingBox(BoundingBox box) {
        int minX = ((int) box.getMinX()) >> 4, minZ = ((int) box.getMinZ()) >> 4;
        int maxX = ((int) box.getMaxX()) >> 4, maxZ = ((int) box.getMaxZ()) >> 4;
        return fromArea(minX, maxX, minZ, maxZ);
    }

    public static long[] fromArea(int minX, int maxX, int minZ, int maxZ) {
        long[] arr = new long[(maxX - minX + 1) * (maxZ - minZ + 1)];
        int idx = 0;
        for (int i = minX; i <= maxX; i++) {
            for (int j = minZ; j <= maxZ; j++) {
                arr[idx++] = ChunkPos.getChunkKey(i, j);
            }
        }
        return arr;
    }
}
