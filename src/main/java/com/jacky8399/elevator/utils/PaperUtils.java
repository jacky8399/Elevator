package com.jacky8399.elevator.utils;

import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

public class PaperUtils {
    public static final boolean IS_PAPER = true;
    public static void teleport(Entity entity, Location location) {
        if (IS_PAPER) {
            // relative flags only matter for the player
            entity.teleport(location,
                    TeleportFlag.EntityState.RETAIN_PASSENGERS,
                    TeleportFlag.Relative.X, TeleportFlag.Relative.Z,
                    TeleportFlag.Relative.YAW, TeleportFlag.Relative.PITCH);
        }
    }
}
