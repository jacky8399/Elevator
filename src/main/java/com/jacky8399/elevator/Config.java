package com.jacky8399.elevator;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;
import java.util.regex.Pattern;

public class Config {
    public static boolean debug = false;

    public static int elevatorCooldown;

    public static int elevatorMaxHeight;

    public static Material elevatorFloorBlock;
    public static Material elevatorScannerBlock;
    public static BlockData elevatorRopeBlock;
    public static Component elevatorItemName;
    public static boolean elevatorScannerDirectional;
    public static boolean elevatorScannerAllowScannerless;

    public static void reload() {

        FileConfiguration config = Elevator.INSTANCE.getConfig();

        debug = config.getBoolean("debug");

        ConfigurationSection elevator = config.getConfigurationSection("elevator");

        elevatorCooldown = elevator.getInt("cooldown");
        elevatorMaxHeight = elevator.getInt("max-height");
        elevatorRopeBlock = getBlockData(elevator, "rope-block");
        elevatorItemName = MiniMessage.miniMessage().deserialize(Objects.requireNonNull(elevator.getString("item-name")));

        var scanner = elevator.getConfigurationSection("scanner");

        elevatorScannerAllowScannerless = scanner.getBoolean("allow-no-scanner");
        elevatorFloorBlock = getBlock(scanner, "floor-block");
        elevatorScannerBlock = getBlock(scanner, "scanner-block");
        elevatorScannerDirectional = scanner.getBoolean("scanner-directional");
    }

    // Utilities

    private static final Pattern RGB_PATTERN = Pattern.compile("&#([0-9A-Fa-f]){6}");
    private static final Pattern RGB_UNTRANSLATE_PATTERN = Pattern.compile("§x((?:§[0-9A-Fa-f]){6})");

    private static String pathOf(ConfigurationSection yaml, String path) {
        String currentPath = yaml.getCurrentPath();
        return currentPath != null && !currentPath.isEmpty() ? currentPath + "." + path : path;
    }

    private static Material getBlock(ConfigurationSection yaml, String path) {
        String string = Objects.requireNonNull(yaml.getString(path), pathOf(yaml, path) + " cannot be null");
        NamespacedKey key = Objects.requireNonNull(NamespacedKey.fromString(string), "Invalid key " + string);
        Material material = Objects.requireNonNull(Registry.MATERIAL.get(key), "Invalid block " + string);
        if (!material.isBlock())
            throw new IllegalArgumentException("Invalid block " + string);
        return material;
    }

    private static BlockData getBlockData(ConfigurationSection yaml, String path) {
        String string = Objects.requireNonNull(yaml.getString(path), pathOf(yaml, path) + " cannot be null");
        return Bukkit.createBlockData(string);
    }
}
