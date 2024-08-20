package com.jacky8399.elevator.utils;

import com.jacky8399.elevator.Config;
import com.jacky8399.elevator.ElevatorController;
import net.kyori.adventure.platform.bukkit.BukkitComponentSerializer;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.jacky8399.elevator.Messages.*;

public sealed interface FloorScan {
    Component getMessage();
    /**
     * Create and display an informative display of the floor scan. <br>
     * Callers are responsible for cleaning up with e.g. with {@link BlockUtils#ensureCleanUp(List, int) BlockUtils.ensureCleanup}
     * @param player The player to show to
     * @return A list of display entities (already shown to the player)
     */
    List<Display> createDisplay(ElevatorController controller, Player player);

    sealed interface Success extends FloorScan {

        BoundingBox scanBox();
        List<ElevatorController.ElevatorFloor> floors();
        int currentFloor();

        default Component getMessage() {
            var floors = floors();
            int currentFloor = currentFloor();

            var builder = Component.text();
            builder.append(renderMessage(msgScanResult, Map.of("floors", Component.text(floors.size()))));
            // show messages in descending order for sensible result in chat
            for (int i = floors.size() - 1; i >= 0; i--) {
                var floor = floors.get(i);
                builder.append(Component.newline(), renderMessage(i == currentFloor ? msgScannedCurrentFloor : msgScannedFloor, Map.of(
                        "name", floor.name(),
                        "y", Component.text(floor.y())
                )));
            }
            return builder.build();
        }

        Color GOLD = Color.fromRGB(0xffaa00);
        @Override
        default List<Display> createDisplay(ElevatorController controller, Player player) {
            List<ElevatorController.ElevatorFloor> floors = floors();
            int currentFloor = currentFloor();

            List<Display> displays = new ArrayList<>(BlockUtils.createLargeOutline(player.getWorld(), scanBox(), player, Color.AQUA));
            for (int i = 0; i < floors.size(); i++) {
                ElevatorController.ElevatorFloor floor = floors.get(i);
                displays.addAll(floor.createDisplay(controller, player, i == currentFloor ? GOLD : Color.YELLOW));
            }
            return displays;
        }
    }

    record Scanner(BoundingBox scanBox, List<ElevatorController.ElevatorFloor> floors, int currentFloor) implements Success {}

    record NoScanner(BoundingBox scanBox, List<ElevatorController.ElevatorFloor> floors, int currentFloor) implements Success {}

    enum NoScannerDisallowed implements FloorScan {
        INSTANCE;

        @Override
        public Component getMessage() {
            return Component.empty();
        }

        @Override
        public List<Display> createDisplay(ElevatorController controller, Player player) {
            BoundingBox cabin = controller.getCabin();
            List<Display> displays = new ArrayList<>(BlockUtils.createLargeOutline(player.getWorld(), cabin, player, Color.RED));
            World world = player.getWorld();
            displays.add(world.spawn(cabin.getCenter().toLocation(world), TextDisplay.class, display -> {
                display.setSeeThrough(true);
                display.setBillboard(Display.Billboard.CENTER);
                Component component = renderMessage(msgErrorScannerNotFound, Map.of(
                        "floor_block", Component.translatable(Objects.requireNonNull(Config.elevatorFloorBlock.getItemTranslationKey())),
                        "scanner_block", Component.translatable(Config.elevatorFloorBlock.getItemTranslationKey())
                ));
                display.setText(BukkitComponentSerializer.legacy().serialize(component));
            }));
            return displays;
        }
    }
}
