package com.jacky8399.elevator;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class Config {
    public static boolean debug = false;

    public static String msgNoFloors;
    public static String msgCurrentFloor;
    public static String msgFloor;
    public static String msgNoFloor;

    public static String msgDefaultGroundFloorName;
    public static String msgDefaultFloorName;
    public static String msgCooldown;

    public static int elevatorCooldown;

    private static final Map<Integer, String> floorNameCache = new HashMap<>();

    public static void reload() {
        floorNameCache.clear();

        FileConfiguration config = Elevator.INSTANCE.getConfig();
        msgNoFloors = getColorString(config, "messages.no-floors");
        msgCurrentFloor = getColorString(config, "messages.current-floor");
        msgFloor = getColorString(config, "messages.floor");
        msgNoFloor = getColorString(config, "messages.no-floor");
        msgDefaultGroundFloorName = getColorString(config, "messages.default-ground-floor-name");
        msgDefaultFloorName = getColorString(config, "messages.default-floor-name");
        msgCooldown = getColorString(config, "messages.cooldown");

        elevatorCooldown = config.getInt("elevator.cooldown");
    }

    // Utilities

    private static final Pattern RGB_PATTERN = Pattern.compile("&#([0-9A-Fa-f]){6}");
    private static String getColorString(ConfigurationSection yaml, String path) {
        String string = yaml.getString(path);
        return string != null ? ChatColor.translateAlternateColorCodes('&',
                RGB_PATTERN.matcher(string).replaceAll(result -> ChatColor.of("#" + result.group(1)).toString())) :
                null;
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
