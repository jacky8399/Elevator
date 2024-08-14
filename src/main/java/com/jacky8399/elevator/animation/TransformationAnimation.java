package com.jacky8399.elevator.animation;

import com.jacky8399.elevator.Config;
import com.jacky8399.elevator.Elevator;
import com.jacky8399.elevator.ElevatorBlock;
import com.jacky8399.elevator.ElevatorController;
import com.jacky8399.elevator.utils.MathUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
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
    public static final int TRANSFORMATION_INTERVAL = 30;
    /*
    Whether to recalculate the transformation and duration on the final stretch
    This will result in a sudden jump due to how transformations work, but might appear smoother
     */
    public static final boolean RECALCULATE_FINAL_STRETCH = false;
    public static final boolean USE_TASKS = false;

    // animation properties
    private final int ticksPerInterval;
    private final int points;
    private final List<ElevatorBlock> elevatorBlocks;
    private final Transformation movingTransformation;
    private final int teleportDistance;

    // transformation and duration for the final interpolation frame
    private final Transformation finalTransformation;
    private final int finalDuration;

    // animation state
    private int nextPoint = 0;
    private int elapsed = 0;

    private final Location tempLocation;

    public TransformationAnimation(ElevatorController controller, List<ElevatorBlock> elevatorBlocks, int movementTime, int speed, Vector velocity) {
        // teleport display entities every 30 blocks travelled
        // to ensure that the display entities don't go out of tracking range
        int yDiff = movementTime * speed / 20;
        boolean down = velocity.getBlockY() < 0;
        teleportDistance = (down ? -TRANSFORMATION_INTERVAL : TRANSFORMATION_INTERVAL);
        ticksPerInterval = 20 * TRANSFORMATION_INTERVAL / speed;
        var scheduler = Bukkit.getScheduler();
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

        if (!USE_TASKS)
            return;
        var tasks = new ArrayList<BukkitTask>();
        for (int i = 0; i <= points; i++) {
            long delay = ticksPerInterval * (long) i; // WHY IS DELAY A LONG????
            if (i != 0) {
                boolean resetTransformation = RECALCULATE_FINAL_STRETCH && i == points;
                // teleport first
                tasks.add(scheduler.runTaskLater(Elevator.INSTANCE, () -> {
                    Location location = new Location(null, 0, 0, 0);
                    ElevatorBlock.forEachDisplay(elevatorBlocks, display -> {
                                display.teleport(display.getLocation(location).add(0, down ? -TRANSFORMATION_INTERVAL : TRANSFORMATION_INTERVAL, 0));
                                if (resetTransformation) {
                                    display.setInterpolationDuration(0);
                                    display.setTransformation(MathUtils.DEFAULT_TRANSFORMATION);
                                }
                            }
                    );
                }, delay + (resetTransformation ? 0 : 2)));
            }
            if (!RECALCULATE_FINAL_STRETCH || i != points) {
                tasks.add(scheduler.runTaskLater(Elevator.INSTANCE, () -> {
                    ElevatorBlock.forEachDisplay(elevatorBlocks, display -> {
                        display.setInterpolationDelay(0);
                        display.setInterpolationDuration(ticksPerInterval);
                        display.setTransformation(movingTransformation);
                    });
                }, delay + 2));
            } else {
                tasks.add(scheduler.runTaskLater(Elevator.INSTANCE, () -> {
                    ElevatorBlock.forEachDisplay(elevatorBlocks, display -> {
                        display.setInterpolationDelay(0);
                        display.setInterpolationDuration(finalDuration);
                        display.setTransformation(finalTransformation);
                    });
                }, delay + 2));
            }
        }

    }

    @Override
    public void immobilize(ElevatorController controller) {

    }

    @Override
    public void tick(ElevatorController controller) {
        if (USE_TASKS)
            return;
        int expectedTick = nextPoint * ticksPerInterval;
        boolean isResetTransformation = RECALCULATE_FINAL_STRETCH && nextPoint == points;
        if (elapsed == expectedTick + (isResetTransformation ? 0 : 2) && nextPoint != 0) {
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
                    display.setTransformation(MathUtils.DEFAULT_TRANSFORMATION);
                });
            }
        }
        if (elapsed == expectedTick + 2) { // 2 tick delay to ensure proper interpolation
            if (!RECALCULATE_FINAL_STRETCH || nextPoint != points) {
                if (Config.debug) {
                    controller.debug("[Animation] At tick %d: updating transformations for point %d".formatted(elapsed, nextPoint));
                }
                ElevatorBlock.forEachDisplay(elevatorBlocks, display -> {
                    display.setInterpolationDelay(0);
                    display.setInterpolationDuration(ticksPerInterval);
                    display.setTransformation(movingTransformation);
                });
            } else { // apply special transformation and duration for final interpolation frame
                if (Config.debug) {
                    controller.debug("[Animation] At tick %d: updating transformations (using final interpolation frame) for point %d".formatted(elapsed, nextPoint));
                }
                ElevatorBlock.forEachDisplay(elevatorBlocks, display -> {
                    display.setInterpolationDelay(0);
                    display.setInterpolationDuration(finalDuration);
                    display.setTransformation(finalTransformation);
                });
            }
            nextPoint++;
        }
        elapsed++;
    }
}
