package com.jacky8399.elevator.utils;

import com.jacky8399.elevator.Elevator;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

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
}
