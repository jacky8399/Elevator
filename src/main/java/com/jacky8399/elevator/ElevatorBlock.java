package com.jacky8399.elevator;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Shulker;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public record ElevatorBlock(BlockPos pos, @Nullable BlockDisplay display, @Nullable Shulker collision, BlockState blockState) {

    public static ElevatorBlock spawnFor(World world, Block base, Block block, Location displayEntityLocation) {
        BlockData data = block.getBlockData();
        BlockState state = block.getState();

        BlockDisplay display = world.spawn(displayEntityLocation, BlockDisplay.class, e -> {
            e.setBlock(data);
            e.setTeleportDuration(0);
        });

        Shulker shulker = null;
        if (block.getType().isOccluding()) {
//            Location location = block.getLocation();
//            shulker = world.spawn(location, Shulker.class, e -> {
//                e.setAI(false);
//                e.setInvisible(true);
//                e.setInvulnerable(true);
//
////                base.addPassenger(e);
//            });
        }

        return new ElevatorBlock(new BlockPos(block.getX() - base.getX(), block.getY() - base.getY(), block.getZ() - base.getZ()), display, shulker, state);
    }

    public static ElevatorBlock spawnVirtualFor(World world, Block base, Block block, BlockData blockData, Location displayEntityLocation) {
        BlockDisplay display = world.spawn(displayEntityLocation, BlockDisplay.class, e -> {
            e.setBlock(blockData);
            e.setTeleportDuration(0);
        });

        return new ElevatorBlock(new BlockPos(block.getX() - base.getX(), block.getY() - base.getY(), block.getZ() - base.getZ()), display, null, null);
    }

//    public static ElevatorBlock spawnBorder(World world, Location location) {
//        Entity base = spawnBase(world, location);
//        Shulker shulker = world.spawn(location, Shulker.class, e -> {
//            e.setAI(false);
//            e.setInvisible(true);
//            e.setInvulnerable(true);
//
//            base.addPassenger(e);
//        });
//
//        return new ElevatorBlock(base, null, shulker);
//    }

    public void remove() {
        if (collision != null)
            collision.remove();
        if (display != null)
            display.remove();
//        stand.remove();
    }

    public static void forEachDisplay(Iterable<ElevatorBlock> iterable, Consumer<BlockDisplay> displayConsumer) {
        for (ElevatorBlock block : iterable) {
            if (block.display != null)
                displayConsumer.accept(block.display);
        }
    }

//    private static Entity spawnBase(World world, Location location) {
//        return world.spawn(location, ArmorStand.class, stand -> {
//            stand.setMarker(true);
//            stand.setInvulnerable(true);
//            stand.setInvisible(true);
//            stand.setCanTick(true);
//            stand.setCanMove(true);
//        });
//    }
}
