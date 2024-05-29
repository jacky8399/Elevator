package com.jacky8399.elevator;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public class Config {
    public static boolean debug = false;

    public static String msgErrorNotInElevator;

    public static String msgNoFloors;
    public static String msgCurrentFloor;
    public static String msgCurrentFloorTemplate;
    public static String msgFloor;
    public static String msgFloorTemplate;
    public static String msgNoFloor;

    public static String msgDefaultGroundFloorName;
    public static String msgDefaultFloorName;
    public static String msgCooldown;
    public static String msgMaintenance;
    public static String msgEnterFloorName;
    public static String msgBeginMaintenance;
    public static String msgEndMaintenance;

    public static String msgScanResult;
    public static String msgScannedFloor;
    public static String msgScannedCurrentFloor;

    public static String msgEditCabinInstructions;
    public static String msgEditCabinPos1;
    public static String msgEditCabinPos2;
    public static String msgEditCabinSuccess;
    public static String msgEditCabinFailed;

    public static int elevatorCooldown;

    public static int elevatorMaxHeight;

    public static Material elevatorFloorBlock;
    public static Material elevatorScannerBlock;
    public static boolean elevatorScannerDirectional;

    private static final Map<Integer, String> floorNameCache = new HashMap<>();

    public static void reload() {
        floorNameCache.clear();

        FileConfiguration config = Elevator.INSTANCE.getConfig();

        debug = config.getBoolean("debug");

        ConfigurationSection messages = Objects.requireNonNull(config.getConfigurationSection("messages"));
        msgErrorNotInElevator = getColorString(messages, "error-not-in-elevator");
        msgNoFloors = getColorString(messages, "no-floors");
        msgCurrentFloor = getColorString(messages, "current-floor");
        msgCurrentFloorTemplate = buildFloorMessageTemplate(msgCurrentFloor);
        msgFloor = getColorString(messages, "floor");
        msgFloorTemplate = buildFloorMessageTemplate(msgFloor);
        msgNoFloor = getColorString(messages, "no-floor");
        msgDefaultGroundFloorName = getColorString(messages, "default-ground-floor-name");
        msgDefaultFloorName = getColorString(messages, "default-floor-name");
        msgCooldown = getColorString(messages, "cooldown");
        msgScanResult = getColorString(messages, "scan.result");
        msgScannedFloor = getColorString(messages, "scan.scanned-floor");
        msgScannedCurrentFloor = getColorString(messages, "scan.scanned-current-floor");
        msgMaintenance = getColorString(messages, "maintenance");
        msgBeginMaintenance = getColorString(messages, "begin-maintenance");
        msgEndMaintenance = getColorString(messages, "end-maintenance");
        msgEnterFloorName = getColorString(messages, "enter-floor-name");

        ConfigurationSection editCabinMessages = Objects.requireNonNull(messages.getConfigurationSection("edit-cabin"));
        msgEditCabinInstructions = getColorString(editCabinMessages, "instructions");
        msgEditCabinPos1 = getColorString(editCabinMessages, "pos1");
        msgEditCabinPos2 = getColorString(editCabinMessages, "pos2");
        msgEditCabinSuccess = getColorString(editCabinMessages, "success");
        msgEditCabinFailed = getColorString(editCabinMessages, "failed");

        ConfigurationSection elevator = config.getConfigurationSection("elevator");

        elevatorCooldown = elevator.getInt("cooldown");

        elevatorMaxHeight = elevator.getInt("max-height");

        var scanner = elevator.getConfigurationSection("scanner");

        elevatorFloorBlock = getBlock(scanner, "floor-block");
        elevatorScannerBlock = getBlock(scanner, "scanner-block");
        elevatorScannerDirectional = scanner.getBoolean("scanner-directional");
    }

    // Utilities

    private static final Pattern RGB_PATTERN = Pattern.compile("&#([0-9A-Fa-f]){6}");
    private static final Pattern RGB_UNTRANSLATE_PATTERN = Pattern.compile("§x((?:§[0-9A-Fa-f]){6})");
    private static String getColorString(ConfigurationSection yaml, String path) {
        return translateColor(yaml.getString(path));
    }

    public static String translateColor(String string) {
        return string != null ? ChatColor.translateAlternateColorCodes('&',
                RGB_PATTERN.matcher(string).replaceAll(result -> ChatColor.of("#" + result.group(1)).toString())) :
                null;
    }

    public static String untranslateColor(String string) {
        return string != null ?
                RGB_UNTRANSLATE_PATTERN.matcher(string).replaceAll("&#$1").replace(ChatColor.COLOR_CHAR, '&') :
                null;
    }

    private static Material getBlock(ConfigurationSection yaml, String path) {
        String string = Objects.requireNonNull(yaml.getString(path), path + " cannot be null");
        NamespacedKey key = Objects.requireNonNull(NamespacedKey.fromString(string), "Invalid key " + string);
        Material material = Objects.requireNonNull(Registry.MATERIAL.get(key), "Invalid block " + string);
        if (!material.isBlock())
            throw new IllegalArgumentException("Invalid block " + string);
        return material;
    }

    private static String buildFloorMessageTemplate(String string) {
        return string.replace("{down}", "\u0001")
                .replace("{current}", "\u0002")
                .replace("{up}", "\u0003");
    }

    public static String getFloorMessage(String template, @Nullable String lowerFloor, @Nullable String currentFloor, @Nullable String upperFloor) {
        lowerFloor = lowerFloor != null ? lowerFloor : msgNoFloor;
        currentFloor = currentFloor != null ? currentFloor : msgNoFloor;
        upperFloor = upperFloor != null ? upperFloor : msgNoFloor;

        StringBuilder sb = new StringBuilder(template);
        for (int i = 0; i < sb.length(); i++) {
            char chr = sb.charAt(i);
            switch (chr) {
                case 1 -> {
                    sb.replace(i, i + 1, lowerFloor);
                    i += lowerFloor.length() - 1;
                }
                case 2 -> {
                    sb.replace(i, i + 1, currentFloor);
                    i += currentFloor.length() - 1;
                }
                case 3 -> {
                    sb.replace(i, i + 1, upperFloor);
                    i += upperFloor.length() - 1;
                }
            }
        }
        return sb.toString();
    }

    public static String getDefaultFloorName(int floorIdx) {
        return floorNameCache.computeIfAbsent(floorIdx, idx -> (idx == 0 ? msgDefaultGroundFloorName : msgDefaultFloorName)
                .replace("{floor}", Integer.toString(floorIdx + 1))
                .replace("{floorMinusOne}", Integer.toString(floorIdx))
        );
    }

}
