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
    public static String msgFloor;
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

    public static Material elevatorFloorBlock;
    public static Material elevatorScannerBlock;
    public static boolean elevatorScannerDirectional;

    private static final Map<Integer, String> floorNameCache = new HashMap<>();

    public static void reload() {
        floorNameCache.clear();

        FileConfiguration config = Elevator.INSTANCE.getConfig();
        ConfigurationSection messages = config.getConfigurationSection("messages");
        msgErrorNotInElevator = getColorString(config, "messages.error-not-in-elevator");
        msgNoFloors = getColorString(config, "messages.no-floors");
        msgCurrentFloor = getColorString(config, "messages.current-floor");
        msgFloor = getColorString(config, "messages.floor");
        msgNoFloor = getColorString(config, "messages.no-floor");
        msgDefaultGroundFloorName = getColorString(config, "messages.default-ground-floor-name");
        msgDefaultFloorName = getColorString(config, "messages.default-floor-name");
        msgCooldown = getColorString(config, "messages.cooldown");
        msgScanResult = getColorString(config, "messages.scan.result");
        msgScannedFloor = getColorString(config, "messages.scan.scanned-floor");
        msgScannedCurrentFloor = getColorString(config, "messages.scan.scanned-current-floor");
        msgMaintenance = getColorString(config, "messages.maintenance");
        msgBeginMaintenance = getColorString(config, "messages.begin-maintenance");
        msgEndMaintenance = getColorString(config, "messages.end-maintenance");
        msgEnterFloorName = getColorString(config, "messages.enter-floor-name");

        ConfigurationSection editCabinMessages = messages.getConfigurationSection("edit-cabin");
        msgEditCabinInstructions = getColorString(editCabinMessages, "instructions");
        msgEditCabinPos1 = getColorString(editCabinMessages, "pos1");
        msgEditCabinPos2 = getColorString(editCabinMessages, "pos2");
        msgEditCabinSuccess = getColorString(editCabinMessages, "success");
        msgEditCabinFailed = getColorString(editCabinMessages, "failed");

        elevatorCooldown = config.getInt("elevator.cooldown");

        elevatorFloorBlock = getBlock(config, "elevator.scanner.floor-block");
        elevatorScannerBlock = getBlock(config, "elevator.scanner.scanner-block");
        elevatorScannerDirectional = config.getBoolean("elevator.scanner.scanner-directional");
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
        return Objects.requireNonNull(Registry.MATERIAL.get(key), "Invalid block " + string);
    }

    public static String getFloorMessage(String raw, @Nullable String lowerFloor, @Nullable String currentFloor, @Nullable String upperFloor) {
        return raw.replace("{down}", lowerFloor != null ? lowerFloor : msgNoFloor)
                .replace("{current}", currentFloor != null ? currentFloor : msgNoFloor)
                .replace("{up}", upperFloor != null ? upperFloor : msgNoFloor);
    }

    public static String getDefaultFloorName(int floorIdx) {
        return floorNameCache.computeIfAbsent(floorIdx, idx -> (idx == 0 ? msgDefaultGroundFloorName : msgDefaultFloorName)
                .replace("{floor}", Integer.toString(floorIdx + 1))
                .replace("{floorMinusOne}", Integer.toString(floorIdx))
        );
    }

}
