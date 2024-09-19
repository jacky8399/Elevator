package com.jacky8399.elevator.utils;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedDataWatcher.WrappedDataWatcherObject;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public class ProtocolUtils {
    private static final ProtocolManager PROTOCOL = ProtocolLibrary.getProtocolManager();

    private static WrappedDataWatcherObject dataWatcher(int id, Class<?> clazz) {
        return new WrappedDataWatcherObject(id, WrappedDataWatcher.Registry.get(clazz));
    }

    private static WrappedDataValue dataValue(WrappedDataWatcherObject dataWatcher, Object value) {
        return WrappedDataValue.fromWrappedValue(dataWatcher.getIndex(), dataWatcher.getSerializer(), value);
    }

    private static WrappedDataValue dataValueRaw(WrappedDataWatcherObject dataWatcher, Object value) {
        return new WrappedDataValue(dataWatcher.getIndex(), dataWatcher.getSerializer(), value);
    }

    public static final WrappedDataWatcherObject INTERPOLATION_DELAY = dataWatcher(8, Integer.class);
    public static final WrappedDataWatcherObject INTERPOLATION_DURATION = dataWatcher(9, Integer.class);
    public static final WrappedDataWatcherObject TRANSLATION = dataWatcher(11, Vector3f.class);
    public static final WrappedDataWatcherObject SCALE = dataWatcher(12, Vector3f.class);
    public static final WrappedDataWatcherObject ROTATION_LEFT = dataWatcher(13, Quaternionf.class);
    public static final WrappedDataWatcherObject ROTATION_RIGHT = dataWatcher(14, Quaternionf.class);

    public static PacketContainer setLocation(Entity entity, Location location) {
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_TELEPORT);
        packet.getEntityModifier(entity.getWorld()).write(0, entity);
        packet.getDoubles() // XYZ
                .write(0, location.getX())
                .write(1, location.getY())
                .write(2, location.getZ());
        packet.getBytes().write(0, (byte) 0).write(1, (byte) 0);
        packet.getBooleans().write(0, false); // on ground
        return packet;
    }
    public static PacketContainer setRelativeLocation(Entity entity, Vector delta) {
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.REL_ENTITY_MOVE);
        packet.getEntityModifier(entity.getWorld()).write(0, entity);
        packet.getShorts() // XYZ
                .write(0, (short) Math.round(delta.getX() * 4096))
                .write(1, (short) Math.round(delta.getY() * 4096))
                .write(2, (short) Math.round(delta.getZ() * 4096));
        packet.getBooleans().write(0, false); // on ground
        return packet;
    }

    public static PacketContainer setDisplayDelay(Display display) {
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_METADATA);
        packet.getEntityModifier(display.getWorld()).write(0, display);
        packet.getDataValueCollectionModifier().write(0, List.of(dataValueRaw(INTERPOLATION_DELAY, 0)));
        return packet;
    }

    public static PacketContainer setDisplayProperties(Display display, Transformation transformation, int interpolationDuration) {
        PacketContainer packet = new PacketContainer(PacketType.Play.Server.ENTITY_METADATA);
        packet.getEntityModifier(display.getWorld()).write(0, display);
        List<WrappedDataValue> values = new ArrayList<>();
        if (display instanceof BlockDisplay blockDisplay) {
//            values.add(dataValue(BLOCK_STATE, blockDisplay.getBlock()));
//            values.add(new WrappedDataValue(23, WrappedDataWatcher.Registry.getBlockDataSerializer(false), blockDisplay.getBlock()));
        }
        values.add(dataValueRaw(INTERPOLATION_DELAY, 0));
        values.add(dataValueRaw(INTERPOLATION_DURATION, interpolationDuration));
        values.add(dataValueRaw(TRANSLATION, transformation.getTranslation()));
        values.add(dataValueRaw(SCALE, transformation.getScale()));
//        values.add(dataValueRaw(ROTATION_LEFT, transformation.getLeftRotation()));
//        values.add(dataValueRaw(ROTATION_RIGHT, transformation.getRightRotation()));
        packet.getDataValueCollectionModifier().write(0, values);
        return packet;
    }

    public static void broadcastBundledPackets(Map<? extends Entity, ? extends Collection<PacketContainer>> packetsByEntity) {
        Map<Player, List<PacketContainer>> packetsByPlayer = new HashMap<>();
        for (var entry : packetsByEntity.entrySet()) {
            List<Player> trackers = PROTOCOL.getEntityTrackers(entry.getKey());
            for (Player tracker : trackers) {
                packetsByPlayer.computeIfAbsent(tracker, ignored -> new ArrayList<>()).addAll(entry.getValue());
            }
        }

        packetsByPlayer.forEach((player, packets) -> {
            var bundle = new PacketContainer(PacketType.Play.Server.BUNDLE);
            bundle.getPacketBundles().write(0, packets);
            PROTOCOL.sendServerPacket(player, bundle, false);
        });
    }

}
