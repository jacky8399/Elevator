package com.jacky8399.elevator;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

public class ElevatorManager {
    public static final Map<Block, ElevatorController> elevators = new LinkedHashMap<>();
    public static final Map<Block, Long> recentlyUnloadedElevators = new HashMap<>();

    public static void cleanUp() {
        elevators.clear();
        playerElevatorCache.clear();
        playerEditingElevator.clear();
        playerEditingElevatorPos.clear();
        managedDoors.clear();
        managedFloors.clear();
    }

    public record PlayerElevator(ElevatorController controller, int floorDiff) {}
    // stores information about players in a stationary elevator
    public static final Map<Player, PlayerElevator> playerElevatorCache = new LinkedHashMap<>();
    public record PlayerMovingElevator(ElevatorController controller) {}
    // stores information about players in a moving elevator
    public static final Map<Player, PlayerMovingElevator> playerMovingElevator = new LinkedHashMap<>();

    // temporary way to let players redefine elevators
    public static final Map<Player, ElevatorController> playerEditingElevator = new WeakHashMap<>();
    public static final Map<Player, Location> playerEditingElevatorPos = new WeakHashMap<>();

    public static final Map<Block, ElevatorController> managedDoors = new HashMap<>();
    // lookup map for elevator floor controller -> floor
    public static final Map<Block, ElevatorController> managedFloors = new HashMap<>();

    public static void removeElevator(ElevatorController controller) {
        elevators.remove(controller.controller);
        playerElevatorCache.values().removeIf(c -> c.controller == controller);
        playerEditingElevator.values().remove(controller);
        for (Block managedDoor : controller.managedDoors) {
            managedDoors.remove(managedDoor);
        }
        for (ElevatorController.ElevatorFloor floor : controller.floors) {
            Block sourceBlock = floor.source();
            if (sourceBlock != null) {
                managedFloors.remove(sourceBlock);
            }
        }
    }

    public static void setUnloadedAt(Block block, long tick) {
        recentlyUnloadedElevators.put(block, tick);
    }
}
