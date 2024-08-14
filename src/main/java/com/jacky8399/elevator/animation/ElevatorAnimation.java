package com.jacky8399.elevator.animation;

import com.jacky8399.elevator.ElevatorBlock;
import com.jacky8399.elevator.ElevatorController;
import com.jacky8399.elevator.utils.PaperUtils;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;

public interface ElevatorAnimation {
    void immobilize(ElevatorController controller);
    void tick(ElevatorController controller);

    default void entityTick(ElevatorController controller, Entity entity, double expectedY, boolean syncSuggested) {
        if (syncSuggested) {
            Location location = entity.getLocation();
            if (entity instanceof LivingEntity && !(entity instanceof Player)) { // LivingEntities interpolate their movement over 3 ticks
                location.setY(expectedY + entity.getVelocity().getY() * 3);
            } else {
                location.setY(expectedY);
            }
            PaperUtils.teleport(entity, location);
        }
    }

    default void onEnterCabin(ElevatorController controller, Entity entity) {}
    default void onLeaveCabin(ElevatorController controller, Entity entity) {}

    @FunctionalInterface
    interface Factory<T extends ElevatorAnimation> {
        /**
         * Schedules a task to handle an elevator moving
         * @param controller The controller
         * @param elevatorBlocks The list of elevator blocks
         * @param movementTime The duration of this movement, in ticks
         * @param speed The absolute speed of this movement, in blocks per second
         * @param velocity The velocity of this movement, in blocks per second
         * @return The scheduled task
         */
        T mobilize(ElevatorController controller, List<ElevatorBlock> elevatorBlocks, int movementTime, int speed, Vector velocity);
    }
}
