package com.jacky8399.elevator;

import com.jacky8399.elevator.utils.BlockUtils;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockVector;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

public class ElevatorManager {
    public static final Map<BlockVector, ElevatorController> elevators = new LinkedHashMap<>();

    public record PlayerElevator(ElevatorController controller, int floorIdx, int slot) {}
    public static final WeakHashMap<Player, PlayerElevator> playerElevatorCache = new WeakHashMap<>();

    public static void removeElevator(ElevatorController controller) {
        elevators.remove(BlockUtils.toVector(controller.controller));
        playerElevatorCache.values().removeIf(c -> c.controller == controller);
    }
}
