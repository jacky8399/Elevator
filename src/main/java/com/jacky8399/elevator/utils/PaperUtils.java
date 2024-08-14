package com.jacky8399.elevator.utils;

import com.jacky8399.elevator.Elevator;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;

public class PaperUtils {
    private static final Object FLAGS;
    private static final MethodHandle TELEPORT_MH;
    static {
        Object flags;
        MethodHandle mh;
        try {
            Class<?> flagClazz = Class.forName("io.papermc.paper.entity.TeleportFlag");
            var lookup = MethodHandles.lookup();

            flags = new TeleportFlag[] {
                    TeleportFlag.EntityState.RETAIN_PASSENGERS, TeleportFlag.EntityState.RETAIN_VEHICLE,
                    TeleportFlag.Relative.X, TeleportFlag.Relative.Z,
                    TeleportFlag.Relative.YAW, TeleportFlag.Relative.PITCH,
            };
            mh = lookup.findVirtual(Entity.class, "teleport", MethodType.methodType(boolean.class, Location.class, flagClazz.arrayType()))
                    .asFixedArity(); // don't use varargs as we already pass flags as an array
        } catch (Exception ex) {
            flags = null;
            mh = null;
            Elevator.LOGGER.warning("You are not using Paper. Paper allows much smoother teleportation and is highly recommended.");
        }
        FLAGS = flags;
        TELEPORT_MH = mh;
    }

    public static void teleport(Entity entity, Location location) {
        if (TELEPORT_MH != null) {
            // relative flags only matter for the player
            try {
                TELEPORT_MH.invoke(entity, location, FLAGS);
            } catch (Throwable throwable) {
                throw new Error("Failed to teleport entity", throwable);
            }
        } else {
            entity.teleport(location);
        }
    }

    private static final boolean STEP_SOUND_SUPPORTS_BLOCK_DATA;
    static {
        boolean supported = false;
        try {
            Field data = Effect.class.getDeclaredField("data");
            supported = data.getType() == List.class && ((List<?>) data.get(Effect.STEP_SOUND)).contains(BlockData.class);
        } catch (Exception ignored) {}
        STEP_SOUND_SUPPORTS_BLOCK_DATA = supported;
    }

    public static void playBlockBreakEffect(World world, Location location, BlockData blockData) {
        world.playEffect(location, Effect.STEP_SOUND, STEP_SOUND_SUPPORTS_BLOCK_DATA ? blockData : blockData.getMaterial());
    }

    private static final MethodHandle BLOCK_STATE_GET_DROPS;
    static {
        MethodHandle getDrops = null;
        try {
            getDrops = MethodHandles.lookup().findVirtual(BlockState.class, "getDrops", MethodType.methodType(Collection.class));
        } catch (Exception ignored) {}
        BLOCK_STATE_GET_DROPS = getDrops;
    }

    public static Collection<ItemStack> getDrops(World world, Location location, BlockState blockState) {
        Collection<ItemStack> drops;
        if (BLOCK_STATE_GET_DROPS != null) {
            try {
                drops = (Collection<ItemStack>) BLOCK_STATE_GET_DROPS.invokeExact(blockState);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        } else {
            // we capture the current state, replace it with the desired block state, collect drops, then rollback to the current state
            // how uncumbersome
            Block block = world.getBlockAt(location);
            BlockState currentState = block.getState();
            blockState.copy(location).update(true, false);
            drops = block.getDrops();
            currentState.update(true, false);
        }
        return drops;
    }
}
