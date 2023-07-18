package com.jacky8399.elevator;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BlockVector;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public final class Elevator extends JavaPlugin {

    public static Elevator INSTANCE;

    public static Logger LOGGER;

    public static final Map<BlockVector, ElevatorController> elevators = new HashMap<>();

    @Override
    public void onEnable() {
        INSTANCE = this;
        LOGGER = getLogger();

        getCommand("elevator").setExecutor(new CommandElevator());

        Bukkit.getPluginManager().registerEvents(new Events(), this);
    }

    @Override
    public void onDisable() {
        for (ElevatorController controller : elevators.values()) {
            controller.immobilize();
        }
        elevators.clear();
    }
}
