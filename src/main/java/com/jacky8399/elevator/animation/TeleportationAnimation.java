package com.jacky8399.elevator.animation;

import com.jacky8399.elevator.Config;
import com.jacky8399.elevator.ElevatorBlock;
import com.jacky8399.elevator.ElevatorController;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.List;

/*
Ugly animation based on wonky teleportation interpolation
 */
public class TeleportationAnimation implements ElevatorAnimation {

    public static final ElevatorAnimation.Factory<TeleportationAnimation> FACTORY = TeleportationAnimation::new;

    public static final int TELEPORT_DURATION = 10;

    int elapsed = 0;
    List<ElevatorBlock> blocks;
    double teleportDistance;

    Location fakeLocation;

    public TeleportationAnimation(ElevatorController controller, List<ElevatorBlock> elevatorBlocks, int movementTime, int speed, Vector velocity) {
        blocks = elevatorBlocks;

        teleportDistance = velocity.getY() * TELEPORT_DURATION / 20d;
        if (Config.debug)
            controller.debug("[TP scheduler] Each teleport will be " + teleportDistance + " blocks");

        ElevatorBlock.forEachDisplay(elevatorBlocks, blockDisplay -> {
            blockDisplay.setTeleportDuration(TELEPORT_DURATION);
        });

        fakeLocation = controller.getController().getLocation();
    }

    @Override
    public void immobilize(ElevatorController controller) {

    }

    @Override
    public void tick(ElevatorController controller) {
        if (elapsed++ % TELEPORT_DURATION != 1)
            return;
        if (Config.debug)
            controller.debug("[TP scheduler] TP at " + elapsed + " ticks");
        ElevatorBlock.forEachDisplay(blocks, display -> display.teleport(display.getLocation(fakeLocation).add(0, teleportDistance, 0)));
    }
}
