package com.jacky8399.elevator.animation;

import com.jacky8399.elevator.Config;
import com.jacky8399.elevator.ElevatorBlock;
import com.jacky8399.elevator.ElevatorController;
import com.jacky8399.elevator.utils.MathUtils;
import com.jacky8399.elevator.utils.PaperUtils;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/*
An elevator animation based on transformations
 */
public class TransformationAnimation implements ElevatorAnimation {

    public static Factory<TransformationAnimation> FACTORY = TransformationAnimation::new;

    /*
    How far must display entities travel before being teleported server-side
    A higher value might reduce the number of packets sent, but is likely to cause issues with the entity tracker
     */
    public static final int TRANSFORMATION_INTERVAL = 32;
    /*
    Whether to recalculate the transformation and duration on the final stretch
    This will result in a sudden jump due to how transformations work, but might appear smoother
     */
    public static final boolean RECALCULATE_FINAL_STRETCH = false;
    public static final int TRANSFORMATION_PADDING_TICKS = 2;
    public static final int COLLISION_UPDATE_INTERVAL = 2;

    // animation properties
    protected final int ticksPerInterval;
    protected final int points;
    protected final List<ElevatorBlock> elevatorBlocks;
    protected final Transformation movingTransformation;
    protected final int teleportDistance;

    // transformation and duration for the final interpolation frame
    protected final Transformation finalTransformation;
    protected final int finalDuration;
    protected final Vector velocity;

    // animation state
    protected int nextPoint = 0;
    protected int elapsed = 0;

    protected final Location tempLocation;

    public TransformationAnimation(ElevatorController controller, List<ElevatorBlock> elevatorBlocks, int movementTime, int speed, Vector velocity) {
        // teleport display entities every 30 blocks travelled
        // to ensure that the display entities don't go out of tracking range
        int yDiff = movementTime * speed / 20;
        this.velocity = velocity.clone();
        boolean down = velocity.getBlockY() < 0;
        teleportDistance = (down ? -TRANSFORMATION_INTERVAL : TRANSFORMATION_INTERVAL);
        ticksPerInterval = 20 * TRANSFORMATION_INTERVAL / speed;
        movingTransformation = new Transformation(new Vector3f(0, down ? -TRANSFORMATION_INTERVAL : TRANSFORMATION_INTERVAL, 0),
                new Quaternionf(), new Vector3f(1), new Quaternionf());
        points = yDiff / TRANSFORMATION_INTERVAL;
        this.elevatorBlocks = elevatorBlocks;

        int finalDistance = (down ? -1 : 1) * yDiff % TRANSFORMATION_INTERVAL;
        finalTransformation = new Transformation(new Vector3f(0, finalDistance, 0),
                new Quaternionf(), new Vector3f(1), new Quaternionf());
        finalDuration = movementTime - ticksPerInterval * points;

        tempLocation = controller.getController().getLocation();


        if (Config.debug) {
            controller.debug("[Animation] No of points: %d".formatted(points));
        }
    }

    @Override
    public void immobilize(ElevatorController controller) {

    }

    @Override
    public void tick(ElevatorController controller) {
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
            ElevatorBlock.forEachDisplay(elevatorBlocks,
                    display -> display.teleport(display.getLocation(tempLocation).add(0, teleportDistance, 0))
            );
            // reset transformation for final interpolation frame
            if (isResetTransformation) {
                if (Config.debug) {
                    controller.debug("[Animation] At tick %d: resetting transformation for final point %d".formatted(elapsed, nextPoint));
                }
                ElevatorBlock.forEachDisplay(elevatorBlocks, display -> {
                    display.setInterpolationDuration(0);
                    display.setTransformation(MathUtils.withTranslation(display.getTransformation(), MathUtils.DEFAULT_TRANSLATION));
                });
            }
        }
        if (elapsed == expectedTick + TRANSFORMATION_PADDING_TICKS) { // 2 tick delay to ensure proper interpolation
            if (!RECALCULATE_FINAL_STRETCH || nextPoint != points) {
                if (Config.debug) {
                    controller.debug("[Animation] At tick %d: updating transformations for point %d".formatted(elapsed, nextPoint));
                }
                ElevatorBlock.forEachDisplay(elevatorBlocks, display -> {
                    display.setInterpolationDelay(0);
                    display.setInterpolationDuration(ticksPerInterval);
                    display.setTransformation(movingTransformation);
                }, textDisplay -> {
                    textDisplay.setInterpolationDelay(0);
                    textDisplay.setInterpolationDuration(ticksPerInterval);
                    textDisplay.setTransformation(MathUtils.withTranslation(textDisplay.getTransformation(), movingTransformation.getTranslation()));
                });
            } else { // apply special transformation and duration for final interpolation frame
                if (Config.debug) {
                    controller.debug("[Animation] At tick %d: updating transformations (using final interpolation frame) for point %d".formatted(elapsed, nextPoint));
                }
                ElevatorBlock.forEachDisplay(elevatorBlocks, display -> {
                    display.setInterpolationDelay(0);
                    display.setInterpolationDuration(finalDuration);
                    display.setTransformation(finalTransformation);
                }, textDisplay -> {
                    textDisplay.setInterpolationDelay(0);
                    textDisplay.setInterpolationDuration(ticksPerInterval);
                    textDisplay.setTransformation(MathUtils.withTranslation(textDisplay.getTransformation(), finalTransformation.getTranslation()));
                });
            }
            nextPoint++;
        }
        elapsed++;
    }
}
