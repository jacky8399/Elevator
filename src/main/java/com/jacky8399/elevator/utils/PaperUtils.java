package com.jacky8399.elevator.utils;

import com.jacky8399.elevator.Elevator;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public class PaperUtils {
    private static final Class<?> FLAG_CLAZZ;
    private static final MethodHandle TELEPORT_MH;
    static {
        Class<?> flagClazz;
        MethodHandle mh;
        try {
            flagClazz = Class.forName("io.papermc.paper.entity.TeleportFlag");
            var lookup = MethodHandles.lookup();

            mh = lookup.findVirtual(Entity.class, "teleport", MethodType.methodType(boolean.class, Location.class, flagClazz.arrayType()));
        } catch (Exception ex) {
            flagClazz = null;
            mh = null;
            Elevator.LOGGER.warning("You are not using Paper. Paper allows much smoother teleportation and is highly recommended.");
        }
        FLAG_CLAZZ = flagClazz;
        TELEPORT_MH = mh;
    }

    public static void teleport(Entity entity, Location location) {
        if (TELEPORT_MH != null) {
            // relative flags only matter for the player
            entity.teleport(location,
                    TeleportFlag.EntityState.RETAIN_PASSENGERS,
                    TeleportFlag.Relative.X, TeleportFlag.Relative.Z,
                    TeleportFlag.Relative.YAW, TeleportFlag.Relative.PITCH);
        } else {
            entity.teleport(location);
        }
    }
}
