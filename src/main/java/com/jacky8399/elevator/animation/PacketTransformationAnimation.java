package com.jacky8399.elevator.animation;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.jacky8399.elevator.Config;
import com.jacky8399.elevator.Elevator;
import com.jacky8399.elevator.ElevatorBlock;
import com.jacky8399.elevator.ElevatorController;
import com.jacky8399.elevator.utils.MathUtils;
import com.jacky8399.elevator.utils.PaperUtils;
import com.jacky8399.elevator.utils.ProtocolUtils;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;

import java.util.*;

public class PacketTransformationAnimation extends TransformationAnimation {

    public static final Factory<PacketTransformationAnimation> FACTORY = PacketTransformationAnimation::new;
    public static final ProtocolManager PROTOCOL = ProtocolLibrary.getProtocolManager();

    static {
        PROTOCOL.addPacketListener(new OffsetPacketListener());
        PROTOCOL.addPacketListener(new MovementPacketListener());
    }

    static Set<Entity> packetInterceptDisplay = Collections.synchronizedSet(new HashSet<>());
    static Map<Entity, Double> packetOffsets = Collections.synchronizedMap(new HashMap<>());

    public PacketTransformationAnimation(ElevatorController controller, List<ElevatorBlock> elevatorBlocks, int movementTime, int speed, Vector velocity) {
        super(controller, elevatorBlocks, movementTime, speed, velocity);
        // send all movement packets ourselves for display entities
        for (ElevatorBlock block : elevatorBlocks) {
            packetInterceptDisplay.add(block.display());
            if (block.textDisplays() != null)
                packetInterceptDisplay.addAll(block.textDisplays());
        }
    }

    @Override
    public void onEnterCabin(ElevatorController controller, Entity entity) {
        super.onEnterCabin(controller, entity);

        Vector offset = velocity.clone().multiply(3d / 20);
        for (Player tracker : PROTOCOL.getEntityTrackers(entity)) {
            PacketContainer tpPacket = ProtocolUtils.setRelativeLocation(entity, offset);
            PROTOCOL.sendServerPacket(tracker, tpPacket, false);
        }
        packetOffsets.put(entity, offset.getY());
    }

    @Override
    public void onLeaveCabin(ElevatorController controller, Entity entity) {
        super.onLeaveCabin(controller, entity);
        Double offset = packetOffsets.remove(entity);
        if (offset != null) {
            PacketContainer tpPacket = ProtocolUtils.setRelativeLocation(entity, new Vector(0, -offset, 0));
            PROTOCOL.broadcastServerPacket(tpPacket, entity, false);
        }
    }

    @Override
    public void immobilize(ElevatorController controller) {
        super.immobilize(controller);
        for (ElevatorBlock block : elevatorBlocks) {
            packetInterceptDisplay.remove(block.display());
            if (block.textDisplays() != null)
                block.textDisplays().forEach(packetInterceptDisplay::remove);
        }
    }

    @Override
    public void entityTick(ElevatorController controller, Entity entity, double expectedY, boolean syncSuggested) {
        // super teleports entities ahead by 3 ticks, we already do that in onEnterCabin
        if (syncSuggested) {
            Location location = entity.getLocation();
            location.setY(expectedY);
            PaperUtils.teleport(entity, location);
        }
    }

    @Override
    public void tick(ElevatorController controller) {
        Multimap<Entity, PacketContainer> packets = ArrayListMultimap.create();
        if (ElevatorBlock.USE_COLLISION_THAT_DOESNT_WORK && elapsed % COLLISION_UPDATE_INTERVAL == 0) {
            for (ElevatorBlock block : elevatorBlocks) {
                ArmorStand collisionBase = block.collisionBase();
                if (collisionBase != null) {
                    Location location = collisionBase.getLocation(tempLocation);
                    // thank you mutable Vectors, very cool
                    PaperUtils.teleport(collisionBase, location.add(0, velocity.getY() * COLLISION_UPDATE_INTERVAL / 20, 0));
                }
            }
        }
        int expectedTick = nextPoint * ticksPerInterval;
        boolean isResetTransformation = RECALCULATE_FINAL_STRETCH && nextPoint == points;
        if (elapsed == expectedTick + (isResetTransformation ? 0 : TRANSFORMATION_PADDING_TICKS) && nextPoint != 0) {
            if (Config.debug) {
                controller.debug("[Animation] At tick %d: teleporting entities for point %d".formatted(elapsed, nextPoint));
            }
            // teleport
            ElevatorBlock.forEachDisplay(elevatorBlocks, display -> {
                // server-side
                Location location = display.getLocation(tempLocation).add(0, teleportDistance, 0);
                display.teleport(location);
                // client-side
                packets.put(display, ProtocolUtils.setLocation(display, location));
            });
            // reset transformation for final interpolation frame
            if (isResetTransformation) {
                if (Config.debug) {
                    controller.debug("[Animation] At tick %d: resetting transformation for final point %d".formatted(elapsed, nextPoint));
                }
                ElevatorBlock.forEachDisplay(elevatorBlocks, display -> {
                    Transformation transformation = MathUtils.withTranslation(display.getTransformation(), MathUtils.DEFAULT_TRANSLATION);
                    packets.put(display, ProtocolUtils.setDisplayProperties(display, transformation, 0));
                });
            }
        }
        if (elapsed == expectedTick + TRANSFORMATION_PADDING_TICKS) { // 2 tick delay to ensure proper interpolation
            if (!RECALCULATE_FINAL_STRETCH || nextPoint != points) {
                if (nextPoint == 0) {
                    ElevatorBlock.forEachDisplay(elevatorBlocks, display -> {
                        packets.put(display, ProtocolUtils.setDisplayProperties(display, movingTransformation, ticksPerInterval));
                    }, textDisplay -> {
                        Transformation transformation = MathUtils.withTranslation(textDisplay.getTransformation(), movingTransformation.getTranslation());
                        packets.put(textDisplay, ProtocolUtils.setDisplayProperties(textDisplay, transformation, ticksPerInterval));
                    });
                } else {
                    ElevatorBlock.forEachDisplay(elevatorBlocks, display -> packets.put(display, ProtocolUtils.setDisplayDelay(display)));
                }

                if (Config.debug) {
                    controller.debug("[Animation] At tick %d: updating transformations for point %d".formatted(elapsed, nextPoint));
                }
            } else { // apply special transformation and duration for final interpolation frame
                if (Config.debug) {
                    controller.debug("[Animation] At tick %d: updating transformations (using final interpolation frame) for point %d".formatted(elapsed, nextPoint));
                }
                ElevatorBlock.forEachDisplay(elevatorBlocks, display -> {
                    packets.put(display, ProtocolUtils.setDisplayProperties(display, finalTransformation, finalDuration));
                }, textDisplay -> {
                    Transformation transformation = MathUtils.withTranslation(textDisplay.getTransformation(), finalTransformation.getTranslation());
                    packets.put(textDisplay, ProtocolUtils.setDisplayProperties(textDisplay, transformation, ticksPerInterval));
                });
            }
            nextPoint++;
        }
        elapsed++;
        if (!packets.isEmpty())
            ProtocolUtils.broadcastBundledPackets(packets.asMap());
    }

    public static class OffsetPacketListener extends PacketAdapter {
        public OffsetPacketListener() {
            super(Elevator.INSTANCE, PacketType.Play.Server.ENTITY_TELEPORT);
        }

        @Override
        public void onPacketSending(PacketEvent event) {
            PacketContainer packet = event.getPacket().deepClone();
            Entity entity = packet.getEntityModifier(event.getPlayer().getWorld()).read(0);
            Double offset = packetOffsets.get(entity);
            if (offset == null)
                return;

            Double original = packet.getDoubles().read(1);
            packet.getDoubles().write(1, original + offset);
            event.setPacket(packet);
        }
    }

    public static class MovementPacketListener extends PacketAdapter {
        public MovementPacketListener() {
            super(Elevator.INSTANCE,
                    PacketType.Play.Server.ENTITY_TELEPORT, PacketType.Play.Server.REL_ENTITY_MOVE, PacketType.Play.Server.REL_ENTITY_MOVE_LOOK
//                    , PacketType.Play.Server.ENTITY_METADATA
            );
        }

        @Override
        public void onPacketSending(PacketEvent event) {
            PacketContainer packet = event.getPacket();
            Entity entity = packet.getEntityModifier(event.getPlayer().getWorld()).read(0);
            if (packetInterceptDisplay.contains(entity)) {
                event.setCancelled(true);
            }
        }
    }
}
