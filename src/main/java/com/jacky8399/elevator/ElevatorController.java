package com.jacky8399.elevator;

import com.jacky8399.elevator.utils.BlockUtils;
import com.jacky8399.elevator.utils.PaperUtils;
import com.jacky8399.elevator.utils.PlayerUtils;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.block.data.type.Observer;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public class ElevatorController {
    public static final Material MATERIAL = Material.DROPPER;

    @NotNull
    World world;

    Block controller;

    @NotNull
    BoundingBox cabin;

    boolean moving;
    List<ElevatorBlock> movingBlocks;

    // cabin entities and their offset relative to the cabin
    Map<LivingEntity, Vector> cabinEntities;

    List<ElevatorFloor> floors = new ArrayList<>();
    int currentFloorIdx = 1;
    record ElevatorFloor(String name, int y, @Nullable Block source) {}

    Vector velocity;
    long movementStartTick;
    long movementEndTick;
    int movementTime;

    public ElevatorController(@NotNull World world, @NotNull Block controller, @NotNull BoundingBox cabin) {
        this.world = world;
        this.controller = controller;
        this.cabin = cabin.clone();
    }

    public void mobilize() {
        if (moving)
            return;
//        Elevator.LOGGER.info("Mobilizing");

        int minX = (int) cabin.getMinX();
        int minY = (int) cabin.getMinY();
        int minZ = (int) cabin.getMinZ();
        int maxX = (int) cabin.getMaxX();
        int maxY = (int) cabin.getMaxY();
        int maxZ = (int) cabin.getMaxZ();

        // scan cabin entities
        var entities = world.getNearbyEntities(cabin, e -> e instanceof LivingEntity);
        this.cabinEntities = new HashMap<>();
        Location temp = controller.getLocation();
        for (var entity : entities) {
            Vector relative = entity.getLocation(temp).subtract(minX, minY, minZ).toVector();
            this.cabinEntities.put((LivingEntity) entity, relative);
        }

        int length = maxX - minX;
        int width = maxZ - minZ;
        int height = maxY - minY;

        movingBlocks = new ArrayList<>(length * width * height + 2 * length * width);
        for (int j = minY; j < maxY; j++) {
            boolean isFloor = j == minY;
            boolean isCeiling = j == maxY - 1;
            for (int i = minX; i < maxX; i++) {
                for (int k = minZ; k < maxZ; k++) {
                    Block block = world.getBlockAt(i, j, k);
                    Material type = block.getType();
                    if (type == Material.AIR || Tag.WITHER_IMMUNE.isTagged(type))
                        continue;

                    movingBlocks.add(ElevatorBlock.spawnFor(world, block));

                    // special treatment for floors and ceilings
                    if ((isFloor || isCeiling) && !type.isOccluding()) {
                        Location location = block.getLocation();
                        if (isFloor)
                            location.add(0.5, BlockUtils.getHighestPoint(block) - 1, 0.5);
                        else
                            location.add(0.5, BlockUtils.getLowestPoint(block), 0.5);
                        if (Config.debug)
                            Elevator.LOGGER.info("Block at %d,%d,%d is %s, new height: %f".formatted(i, j, k, type, location.getY()));
                        movingBlocks.add(ElevatorBlock.spawnBorder(world, location));
                    }

                    block.setType(Material.AIR, false);
                }
            }
        }
        moving = true;
    }

    public void immobilize() {
        if (!moving)
            return;

        if (Config.debug)
            Elevator.LOGGER.info("Immobilizing");

        Location location = controller.getLocation();
        for (ElevatorBlock block : movingBlocks) {
            if (block.display() != null) {
                block.stand().getLocation(location);
                // I love floating point errors
                location.setY(Math.round(location.getY()));
                Block solidBlock = location.getBlock();
                BlockData toPlace = block.display().getBlock();
                if (solidBlock.getType() == Material.AIR)
                    solidBlock.setBlockData(toPlace, true);
                else
                    world.dropItemNaturally(location, new ItemStack(toPlace.getPlacementMaterial()));
            }
            block.remove();
        }
        movingBlocks.clear();

        // round cabin
        cabin.resize(Math.round(cabin.getMinX()), Math.round(cabin.getMinY()), Math.round(cabin.getMinZ()),
                Math.round(cabin.getMaxX()), Math.round(cabin.getMaxY()), Math.round(cabin.getMaxZ()));

        Vector cabinMin = cabin.getMin();

        Location temp = controller.getLocation();
        for (var entry : cabinEntities.entrySet()) {
            LivingEntity entity = entry.getKey();
            Vector offset = entry.getValue();

            entity.setGravity(true);
            entity.setFallDistance(0);
            if (entity instanceof Player player) {
                PlayerUtils.unsetAllowFlight(player);
            }
            if (velocity.getY() > 0) {
                entity.getLocation(temp).setY(cabinMin.getY() + offset.getY());
                PaperUtils.teleport(entity, temp);
            }
        }
        cabinEntities.clear();
//        if (velocity != null) {
//            long ticksElapsed = world.getGameTime() - movementStartTick;
//            cabin.shift(velocity.clone().multiply(ticksElapsed / 20d));
//        }

        velocity = null;
        moving = false;
    }

    public void save() {
        if (Config.debug)
            Elevator.LOGGER.info("Saving " + controller);
        TileState state = (TileState) controller.getState();
        state.getPersistentDataContainer().set(ELEVATOR_CONTROLLER, STORAGE, this);
        state.update();
    }

    @Nullable
    public static ElevatorController load(Block block, TileState state) {
        if (block.getType() != MATERIAL)
            return null;
        try {
            ElevatorController controller = state.getPersistentDataContainer().get(ELEVATOR_CONTROLLER, STORAGE);
            if (controller == null)
                return null;
            if (Config.debug)
                Elevator.LOGGER.info("Loaded " + block);
            controller.controller = block;
            controller.world = block.getWorld();
            controller.scanFloors();
            return controller;
        } catch (Exception ex) {
            Elevator.LOGGER.severe("Failed to load ElevatorController: " + ex);
            ex.printStackTrace();
            return null;
        }
    }

    public void scanFloors() {
        int minX = (int) cabin.getMinX();
        int minY = (int) cabin.getMinY();
        int minZ = (int) cabin.getMinZ();
        int maxX = (int) cabin.getMaxX();
        int maxY = (int) cabin.getMaxY();
        int maxZ = (int) cabin.getMaxZ();

        record FloorScanner(Block block, BlockFace face) {}
        List<FloorScanner> scanners = new ArrayList<>();

        for (int j = minY; j < maxY; j++) {
            for (int i = minX; i < maxX; i++) {
                for (int k = minZ; k < maxZ; k++) {
                    Block block = world.getBlockAt(i, j, k);

                    if (block.getType() == Material.OBSERVER) {
                        BlockData data = block.getBlockData();
                        scanners.add(new FloorScanner(block, ((Observer) data).getFacing()));
                    }
                }
            }
        }

        Location temp = controller.getLocation();
        int shaftTop = world.getMaxHeight(), shaftBottom = world.getMinHeight();
        for (int i = minX; i < maxX; i++) {
            temp.setX(i);
            for (int k = minZ; k < maxZ; k++) {
                temp.setZ(k);

                String string = "Elevator (" + i + "," + k + ")";

                temp.setY(minY - 1);
                int currentBottom = BlockUtils.rayTraceVertical(temp, false) + 1;
                string += ": bottom= " + currentBottom;
                shaftBottom = Math.max(shaftBottom, currentBottom);

                temp.setY(maxY);
                int currentTop = BlockUtils.rayTraceVertical(temp, true);
                string += ", top=" + currentTop;
                shaftTop = Math.min(shaftTop, currentTop);

                if (Config.debug)
                    Elevator.LOGGER.info(string);
            }
        }

        floors.clear();
        if (scanners.size() == 0) {
            // no indicators, use top and bottom of the shaft
            int topLevel = shaftTop - maxY + minY;
            if (topLevel != shaftBottom)
                floors.add(new ElevatorFloor(String.valueOf(1), topLevel, null));
            floors.add(new ElevatorFloor(String.valueOf(2), minY, null));
            floors.add(new ElevatorFloor(String.valueOf(3), shaftBottom, null));
            currentFloorIdx = 1;
            if (Config.debug)
                Elevator.LOGGER.info("No scanner, floors: " + floors);
        } else {
            record TempFloor(int cabinY, Block source, String name) {}
            List<TempFloor> tempFloors = new ArrayList<>();
            for (var scanner : scanners) {
                int y = scanner.block.getY();
                Location location = scanner.block.getRelative(scanner.face).getLocation();
//                BlockFace checkFace = scanner.face.getOppositeFace();
                for (int i = shaftBottom + y - minY, end = shaftTop - (maxY - y); i <= end; i++) {
                    location.setY(i);
                    Block block = location.getBlock();
                    if (block.getType() == Material.NOTE_BLOCK) {
                        if (Config.debug)
                            Elevator.LOGGER.info("Found floor at " + block);

                        // look for a sign and use it as the floor name
                        String floorName = null;
                        for (BlockFace face : BlockFace.values()) {
                            Block side = block.getRelative(face);
                            if (Tag.SIGNS.isTagged(side.getType())) {
                                Sign sign = (Sign) side.getState();

                                floorName = sign.getSide(Side.FRONT).getLine(0);
                            }
                        }
                        tempFloors.add(new TempFloor(i - minY + y, block, floorName));
                    }
                }
            }
            tempFloors.sort(Collections.reverseOrder(Comparator.comparingInt(TempFloor::cabinY)));

            int closestFloorDist = world.getMaxHeight();
            int closestFloor = -1;
            for (int i = 0; i < tempFloors.size(); i++) {
                TempFloor floor = tempFloors.get(i);
                int floorY = floor.cabinY;
                String realName = floor.name != null ? floor.name : String.valueOf(tempFloors.size() - i);
                floors.add(new ElevatorFloor(realName, floorY, floor.source));
                if (Math.abs(floorY - minY) < closestFloorDist) {
                    closestFloorDist = Math.abs(floorY - minY);
                    closestFloor = i;
                }
            }
            currentFloorIdx = closestFloor;

            if (Config.debug)
                Elevator.LOGGER.info("Floors: " + floors + ", current: " + currentFloorIdx);
        }
    }

    public void moveUp() {
        scanFloors();

        int minY = (int) cabin.getMinY();

        // check current floor and upper floor in case the current floor changed
        ElevatorFloor currentFloor = floors.get(this.currentFloorIdx);
        if (currentFloor.y > minY) {
            moveTo(currentFloor.y);
            return;
        }

        if (this.currentFloorIdx != 0) {
            ElevatorFloor upperFloor = floors.get(this.currentFloorIdx - 1);
            moveTo(upperFloor.y);
        }
    }

    public void moveDown() {
        scanFloors();
        int minY = (int) cabin.getMinY();

        // check current floor and lower floor in case the current floor changed
        ElevatorFloor currentFloor = floors.get(this.currentFloorIdx);
        if (currentFloor.y < minY) {
            moveTo(currentFloor.y);
            return;
        }

        if (this.currentFloorIdx != floors.size() - 1) {
            ElevatorFloor lowerFloor = floors.get(this.currentFloorIdx + 1);
            moveTo(lowerFloor.y);
        }
    }


    private static final int SPEED = 5;
    private static final int TIME_MULTIPLIER = 20 / SPEED;
    private void moveTo(int y) {
        int yDiff = y - (int) cabin.getMinY();
        if (yDiff == 0)
            return;
        if (Config.debug)
            Elevator.LOGGER.info("Moving to " + y + " (" + yDiff + " blocks)");
        velocity = new Vector(0, yDiff > 0 ? SPEED : -SPEED, 0);
        movementStartTick = world.getGameTime();
//        movementEndTick = movementStartTick + Math.abs(yDiff) * 20L;
        movementTime = Math.abs(yDiff) * TIME_MULTIPLIER + 1;

        mobilize();
        if (Config.debug)
            Elevator.LOGGER.info("Moving " + movingBlocks.size() + " blocks at " + velocity + " for " + movementTime + " ticks");
        doMove();
    }

    public void tick() {
        if (controller.getType() != MATERIAL) {
            ElevatorManager.removeElevator(this); // don't save
            return;
        }

        if (moving) {
            if (movementTime == 0) {
                immobilize();
            } else {
                doMove();
            }
        } else if ((world.getGameTime() & 1) == 0) {
            // check for elevator calls
            for (ElevatorFloor floor : floors) {
                if (floor.source != null) {
                    BlockData data = floor.source.getBlockData();
                    if (data.getMaterial() == Material.NOTE_BLOCK && ((NoteBlock) data).isPowered()) {
                        moveTo(floor.y);
                    }
                }
            }
            // player check
            var players = world.getNearbyEntities(cabin.getCenter().toLocation(world),
                    cabin.getWidthX() / 2, cabin.getHeight() / 2, cabin.getWidthZ() / 2,
                    player -> player instanceof Player && cabin.contains(player.getLocation().toVector()));
            for (Entity entity : players) {
                Player player = (Player) entity;
                boolean jumping = player.getVelocity().getY() > 0;
                var cache = ElevatorManager.playerElevatorCache.get(player);
                int floorIdx;
                if (cache == null || cache.floorIdx() == currentFloorIdx) {
                    if (jumping || player.isSneaking()) {
                        // rescan then move up or down a floor
                        scanFloors();
                        int newFloor = currentFloorIdx + (jumping ? -1 : 1);
                        if (newFloor >= 0 && newFloor < floors.size()) {
                            // reset the selection
                            ElevatorManager.playerElevatorCache.remove(player);

                            currentFloorIdx = newFloor;
                            moveTo(floors.get(newFloor).y);
                            return;
                        }
                    }
                    // check for scrolling
                    int slot = player.getInventory().getHeldItemSlot();
                    int lastSlot = cache != null ? cache.slot() : slot;
                    floorIdx = lastSlot != slot ? Math.floorMod(currentFloorIdx + slot - lastSlot, floors.size()) : currentFloorIdx;
                    ElevatorManager.playerElevatorCache.put(player,
                            new ElevatorManager.PlayerElevator(this, floorIdx, slot));

                    player.sendActionBar("▶ " + floors.get(floorIdx).name + " | Jump or crouch to move | Scroll to select a floor");
                } else {
                    floorIdx = cache.floorIdx();
                    int lastSlot = cache.slot(), slot = player.getInventory().getHeldItemSlot();
                    if (lastSlot != slot) {
                        floorIdx = Math.floorMod(floorIdx + slot - lastSlot, floors.size());
                        ElevatorManager.playerElevatorCache.put(player,
                                new ElevatorManager.PlayerElevator(this, floorIdx, slot));
                    }

                    ElevatorFloor floor = floorIdx >= 0 && floorIdx < floors.size() ? floors.get(floorIdx) : null;
                    if (floor != null && (jumping || player.isSneaking())) {
                        // reset the selection
                        ElevatorManager.playerElevatorCache.remove(player);

                        currentFloorIdx = floorIdx;
                        moveTo(floor.y);
                    }


                    StringJoiner joiner = new StringJoiner(" | ");
                    if (floorIdx != floors.size() - 1)
                        joiner.add("▼ " + floors.get(floorIdx + 1).name);
                    joiner.add("▶ " + floors.get(floorIdx).name);
                    if (floorIdx != 0)
                        joiner.add("▲ " + floors.get(floorIdx - 1).name);


                    player.sendActionBar(TextComponent.fromLegacyText(joiner.toString()));
                }
            }
        }
    }

    void doMove() {
        movementTime--;
        // sync armor stands
        Location temp = controller.getLocation();
        Vector delta = velocity.clone().multiply(1/20f);
        for (ElevatorBlock block : movingBlocks) {
            PaperUtils.teleport(block.stand(), block.stand().getLocation(temp).add(delta));
        }

        cabin.shift(delta);
        Vector cabinMin = cabin.getMin();

        // simulate gravity
//        double entityYVel = delta.getY() > 0 ? delta.getY() : delta.getY() * 2;
        double entityYVel = delta.getY();
        for (var iter = cabinEntities.entrySet().iterator(); iter.hasNext(); ) {
            var entry = iter.next();
            LivingEntity entity = entry.getKey();
            Vector offset = entry.getValue();

            if (entity.isDead()) {
                iter.remove();
                continue;
            }

            if (world.getGameTime() % 20 == 0) {
                // force synchronize location
                entity.getLocation(temp);
                temp.setY(cabinMin.getY() + offset.getY());
                PaperUtils.teleport(entity, temp);
            }
            entity.setGravity(false);
            if (entity instanceof Player player) {
                PlayerUtils.setAllowFlight(player);
                player.setFlying(false);
            }
            Vector velocity = entity.getVelocity();
            velocity.setY(entityYVel);
            entity.setVelocity(velocity);
        }
    }

    void showOutline(Collection<Player> players) {
        BlockDisplay display = world.spawn(cabin.getMin().toLocation(world).add(-0.1, -0.1, -0.1), BlockDisplay.class, blockDisplay -> {
            blockDisplay.setVisibleByDefault(false);
            players.forEach(player -> player.showEntity(Elevator.INSTANCE, blockDisplay));

            blockDisplay.setBlock(Material.GLASS.createBlockData());
            var scale = new Vector3f((float) cabin.getWidthX() + 0.2f, (float) cabin.getHeight() + 0.2f, (float) cabin.getWidthZ() + 0.2f);
            blockDisplay.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), scale,
                    new Quaternionf()));
            blockDisplay.setBrightness(new Display.Brightness(15, 15));
            blockDisplay.setGlowing(true);
        });
        Bukkit.getScheduler().runTaskLater(Elevator.INSTANCE, display::remove, 10 * 20);
    }

    public static final NamespacedKey ELEVATOR_CONTROLLER = new NamespacedKey(Elevator.INSTANCE, "elevator_controller");
    private static final PersistentDataType<PersistentDataContainer, ElevatorController> STORAGE = new Storage();
    private static class Storage implements PersistentDataType<PersistentDataContainer, ElevatorController> {

        private static NamespacedKey key(String string) {
            return new NamespacedKey(Elevator.INSTANCE, string);
        }

        private static final NamespacedKey DATA_VERSION_KEY = key("data_version");
        private static final int DATA_VERSION = 1;
        private static final NamespacedKey CABIN_KEY = key("cabin");

        @NotNull
        @Override
        public Class<PersistentDataContainer> getPrimitiveType() {
            return PersistentDataContainer.class;
        }

        @NotNull
        @Override
        public Class<ElevatorController> getComplexType() {
            return ElevatorController.class;
        }

        @NotNull
        @Override
        public PersistentDataContainer toPrimitive(@NotNull ElevatorController complex, @NotNull PersistentDataAdapterContext context) {
            PersistentDataContainer container = context.newPersistentDataContainer();
            container.set(DATA_VERSION_KEY, INTEGER, DATA_VERSION);
            BoundingBox cabin = complex.cabin;
            int[] cabinCoords = new int[] {
                    (int) cabin.getMinX(), (int) cabin.getMinY(), (int) cabin.getMinZ(),
                    (int) cabin.getMaxX(), (int) cabin.getMaxY(), (int) cabin.getMaxZ()
            };
            container.set(CABIN_KEY, INTEGER_ARRAY, cabinCoords);
            return container;
        }

        @NotNull
        @Override
        public ElevatorController fromPrimitive(@NotNull PersistentDataContainer primitive, @NotNull PersistentDataAdapterContext context) {
            int dataVersion = primitive.get(DATA_VERSION_KEY, INTEGER);

            return switch (dataVersion) {
                case DATA_VERSION -> {
                    int[] cabinCoords = primitive.get(CABIN_KEY, INTEGER_ARRAY);
                    BoundingBox box = new BoundingBox(cabinCoords[0], cabinCoords[1], cabinCoords[2], cabinCoords[3], cabinCoords[4], cabinCoords[5]);
                    yield new ElevatorController(null, null, box);
                }
                default -> throw new IllegalArgumentException("Invalid data version " + dataVersion);
            };

//            ElevatorController controller = new ElevatorController();
//            return null;
        }
    }
}
