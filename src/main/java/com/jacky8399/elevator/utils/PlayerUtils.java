package com.jacky8399.elevator.utils;

import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class PlayerUtils {
    private static final Set<Player> flight = Collections.newSetFromMap(new WeakHashMap<>());
    public static void setAllowFlight(Player player) {
        if (!player.getAllowFlight()) {
            flight.add(player);
            player.setAllowFlight(true);
        }
    }

    public static void unsetAllowFlight(Player player) {
        if (flight.remove(player)) {
            player.setAllowFlight(false);
        }
    }
}
