package com.jacky8399.elevator;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Shulker;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public record ElevatorBlock(Entity stand, @Nullable BlockDisplay display, @Nullable Shulker collision) {
    private static final Transformation DISPLAY_TRANSFORMATION =
            new Transformation(new Vector3f(-0.5f, 0, -0.5f), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf());

    public static ElevatorBlock spawnFor(World world, Block block) {
        BlockData data = block.getBlockData();

        Location location = block.getLocation().add(0.5, 0, 0.5);
        Entity base = spawnBase(world, location);

        BlockDisplay display = world.spawn(location, BlockDisplay.class, e -> {
            e.setBlock(data);
            e.setTransformation(DISPLAY_TRANSFORMATION);

            base.addPassenger(e);
        });

        Shulker shulker;
        if (false && block.getType().isOccluding()) {
            shulker = world.spawn(location, Shulker.class, e -> {
                e.setAI(false);
                e.setInvisible(true);
                e.setInvulnerable(true);

                base.addPassenger(e);
            });
        } else {
            shulker = null;
        }

        return new ElevatorBlock(base, display, shulker);
    }

    public static ElevatorBlock spawnBorder(World world, Location location) {
        Entity base = spawnBase(world, location);
        Shulker shulker = world.spawn(location, Shulker.class, e -> {
            e.setAI(false);
            e.setInvisible(true);
            e.setInvulnerable(true);

            base.addPassenger(e);
        });

        return new ElevatorBlock(base, null, shulker);
    }

    public void remove() {
        if (collision != null)
            collision.remove();
        if (display != null)
            display.remove();
        stand.remove();
    }

    private static Entity spawnBase(World world, Location location) {
        return world.spawn(location, ArmorStand.class, stand -> {
            stand.setMarker(true);
            stand.setInvulnerable(true);
            stand.setInvisible(true);
            stand.setCanTick(true);
        });
    }
}
