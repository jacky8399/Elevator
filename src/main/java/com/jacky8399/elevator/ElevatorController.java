package com.jacky8399.elevator;

import com.jacky8399.elevator.utils.BlockUtils;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ElevatorController {

    @NotNull
    World world;

    Location controller;

    @NotNull
    BoundingBox cabin;

    boolean moving;
    List<ElevatorBlock> movingBlocks;

    public ElevatorController(@NotNull World world, @NotNull Location controller, @NotNull BoundingBox cabin) {
        this.world = world;
        this.controller = controller.clone();
        this.cabin = cabin.clone();
    }

    Collection<Entity> getCabinEntities() {
        return world.getNearbyEntities(cabin);
    }

    void mobilize() {
        if (moving)
            return;
        int minX = (int) cabin.getMinX();
        int minY = (int) cabin.getMinY();
        int minZ = (int) cabin.getMinZ();
        int maxX = (int) cabin.getMaxX();
        int maxY = (int) cabin.getMaxY();
        int maxZ = (int) cabin.getMaxZ();

        int length = maxX - minX;
        int width = maxZ - minZ;
        int height = maxY - minY;

        movingBlocks = new ArrayList<>(length * width * height + 2 * length * width);
        for (int j = minY; j < maxY; j++) {
            boolean isFloor = j == minY;
            boolean isCeiling = j == maxY - 1;
            for (int i = minX; i < maxX; i++) {
                for (int k = minZ; k < maxZ; k++) {
                    Block block = world.getBlockAt(i, j, k);
                    Material type = block.getType();
                    if (type == Material.AIR || Tag.WITHER_IMMUNE.isTagged(type))
                        continue;

                    movingBlocks.add(ElevatorBlock.spawnFor(world, block));

                    // special treatment for floors and ceilings
                    if ((isFloor || isCeiling) && !type.isOccluding()) {
                        Location location = block.getLocation();
                        if (isFloor)
                            location.add(0.5, BlockUtils.getHighestPoint(block) - 1, 0.5);
                        else
                            location.add(0.5, BlockUtils.getLowestPoint(block), 0.5);
                        Elevator.LOGGER.info("Block at %d,%d,%d is %s, new height: %f".formatted(i, j, k, type, location.getY()));
                        movingBlocks.add(ElevatorBlock.spawnBorder(world, location));
                    }

                    block.setType(Material.AIR, false);
                }
            }
        }
        moving = true;
    }

    void immobilize() {
        if (!moving)
            return;

        Location location = controller.clone();
        for (ElevatorBlock block : movingBlocks) {
            if (block.display() != null) {
                block.stand().getLocation(location).getBlock().setBlockData(block.display().getBlock(), false);
            }
            block.remove();
        }

        moving = false;
    }

    void tick() {

    }

    void showOutline(Collection<Player> players) {
        BlockDisplay display = world.spawn(cabin.getMin().toLocation(world).add(-0.1, -0.1, -0.1), BlockDisplay.class, blockDisplay -> {
            blockDisplay.setVisibleByDefault(false);
            players.forEach(player -> player.showEntity(Elevator.INSTANCE, blockDisplay));

            blockDisplay.setBlock(Material.GLASS.createBlockData());
            var scale = new Vector3f((float) cabin.getWidthX() + 0.2f, (float) cabin.getHeight() + 0.2f, (float) cabin.getWidthZ() + 0.2f);
            blockDisplay.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), scale,
                    new Quaternionf()));
            blockDisplay.setBrightness(new Display.Brightness(15, 15));
            blockDisplay.setGlowing(true);
        });
        Bukkit.getScheduler().runTaskLater(Elevator.INSTANCE, display::remove, 10 * 20);
    }
}
