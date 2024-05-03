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

    public record PlayerElevator(ElevatorController controller, int floorIdx) {}
    public static final WeakHashMap<Player, PlayerElevator> playerElevatorCache = new WeakHashMap<>();

    // temporary way to let players redefine elevators
    public static final WeakHashMap<Player, ElevatorController> playerEditingElevator = new WeakHashMap<>();
    public static final WeakHashMap<Player, Location> playerEditingElevatorPos = new WeakHashMap<>();

    public static final Map<Block, ElevatorController> managedDoors = new HashMap<>();
    // lookup map for elevator floor controller -> floor
    public static final Map<Block, ElevatorController> managedFloors = new HashMap<>();

    public static void removeElevator(ElevatorController controller) {
        elevators.remove(controller.controller);
        playerElevatorCache.values().removeIf(c -> c.controller == controller);
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
}
