package com.jacky8399.elevator;

import com.jacky8399.elevator.utils.BlockInteraction;
import com.jacky8399.elevator.utils.BlockUtils;
import com.jacky8399.elevator.utils.MathUtils;
import net.kyori.adventure.platform.bukkit.BukkitComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.HangingSign;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.*;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Consumer;

public record ElevatorBlock(BlockPos pos, @Nullable BlockDisplay display, BlockState blockState,
                            @Nullable List<TextDisplay> textDisplays,
                            @Nullable ArmorStand collisionBase, @Nullable Shulker collision,
                            @NotNull Set<Flag> flags) {
    public static Set<Entity> excludedEntities = new HashSet<>();
    public static final boolean USE_COLLISION_THAT_DOESNT_WORK = true;

    public static ElevatorBlock spawnFor(World world, Block base, Set<Integer> noCollisionYs, Block block, Vector offset) {
        Location location = block.getLocation().add(offset);

        BlockData data = block.getBlockData();
        BlockState state = block.getState();

        BlockDisplay display = world.spawn(location, BlockDisplay.class, e -> {
            e.setBlock(data);
            e.setTeleportDuration(0);
            e.setPersistent(false);
        });
        Set<Flag> flags = Set.of();
        List<TextDisplay> textDisplays = null;
        if (state instanceof Sign sign) {
            textDisplays = spawnSignText(sign, offset);
            if (sign.getSide(Side.FRONT).isGlowingText() || sign.getSide(Side.BACK).isGlowingText() ||
                    Config.interactionsCabin.contains(BlockInteraction.SIGNS)) {
                flags = Set.of(Flag.UPDATE_TEXT);
            }
        }

        ArmorStand collisionBase;
        Shulker collision;
        if (USE_COLLISION_THAT_DOESNT_WORK && !noCollisionYs.contains(block.getY()) && block.getType().isOccluding()) {
            Location centered = location.clone().add(0.5, 0, 0.5);
            collisionBase = world.spawn(centered, ArmorStand.class, e -> {
                e.setInvisible(true);
                e.setInvulnerable(true);
                e.setMarker(true);
                e.setPersistent(false);
            });

            collision = world.spawn(centered, Shulker.class, e -> {
                e.setAI(false);
                e.setInvisible(true);
                e.setInvulnerable(true);
                e.setPersistent(false);

                collisionBase.addPassenger(e);
            });
            excludedEntities.add(collisionBase);
            excludedEntities.add(collision);
        } else {
            collisionBase = null;
            collision = null;
        }

        BlockPos pos = new BlockPos(block.getX() - base.getX(), block.getY() - base.getY(), block.getZ() - base.getZ());
        return new ElevatorBlock(pos, display, state, textDisplays, collisionBase, collision, flags);
    }

    public static ElevatorBlock spawnVirtualFor(World world, Block base, Block block, BlockData blockData, Location displayEntityLocation) {
        BlockDisplay display = world.spawn(displayEntityLocation, BlockDisplay.class, e -> {
            e.setBlock(blockData);
            e.setTeleportDuration(0);
            e.setPersistent(false);
        });

        return new ElevatorBlock(new BlockPos(block.getX() - base.getX(), block.getY() - base.getY(), block.getZ() - base.getZ()),
                display, null, null, null, null, Set.of());
    }

    static List<TextDisplay> spawnSignText(Sign sign, Vector offset) {
        World world = sign.getWorld();
        var list = new ArrayList<TextDisplay>(2);
        TextDisplay front = spawnSignSideText(world, offset, sign, Side.FRONT);
        if (front != null)
            list.add(front);
        TextDisplay back = spawnSignSideText(world, offset, sign, Side.BACK);
        if (back != null)
            list.add(back);
        return list;
    }

    private static final Color TRANSPARENT = Color.fromARGB(0x00000000);
    private static final Vector3f SIGN_SCALE = new Vector3f(0.425f);
    private static final Vector3f HANGING_SIGN_SCALE = new Vector3f(0.55f);
    @Nullable
    private static TextDisplay spawnSignSideText(World world, Vector offset, Sign sign, Side side) {
        SignSide signSide = sign.getSide(side);
        // check if side empty
        String[] lines = signSide.getLines();
        if (Arrays.stream(lines).allMatch(String::isBlank))
            return null;
        DyeColor color = signSide.getColor();
        if (color == null) color = DyeColor.BLACK;
        TextColor textColor = TextColor.color(color.getColor().asRGB());
        List<Component> components = new ArrayList<>(Collections.nCopies(4, Component.empty()));
        for (int i = 0; i < lines.length; i++) {
            TextComponent component = BukkitComponentSerializer.legacy().deserialize(lines[i]);
            components.set(i, component.colorIfAbsent(textColor)); // use sign color if colorless
        }

        String legacyString = BukkitComponentSerializer.legacy().serialize(Component.join(JoinConfiguration.newlines(), components));

        Location location = BlockUtils.getSignLocation(sign.getBlock(), sign.getBlockData(), side);
        return world.spawn(location.add(offset), TextDisplay.class, display -> {
            display.setTeleportDuration(0);
            display.setBackgroundColor(TRANSPARENT);
//            display.setText(String.join("\n", signSide.getLines()));
            display.setText(legacyString);
            // has to have 4 lines to ensure height is correct
            display.setTransformation(MathUtils.scaleBy(sign instanceof HangingSign ? HANGING_SIGN_SCALE : SIGN_SCALE));
        });
    }

    public void remove() {
        if (collision != null)
            collision.remove();
        if (display != null)
            display.remove();
        if (textDisplays != null) {
            for (TextDisplay textDisplay : textDisplays) {
                textDisplay.remove();
            }
        }
        if (collision != null) {
            collision.remove();
            excludedEntities.remove(collision);
        }
        if (collisionBase != null) {
            collisionBase.remove();
            excludedEntities.remove(collisionBase);
        }
//        stand.remove();
    }

    public static void forEachDisplay(Iterable<ElevatorBlock> iterable, Consumer<? super Display> displayConsumer) {
        forEachDisplay(iterable, displayConsumer, displayConsumer);
    }

    public static void forEachDisplay(Iterable<ElevatorBlock> iterable, Consumer<? super BlockDisplay> blockDisplayConsumer, Consumer<? super TextDisplay> textDisplayConsumer) {
        for (ElevatorBlock block : iterable) {
            if (block.display != null)
                blockDisplayConsumer.accept(block.display);
            if (block.textDisplays != null)
                for (TextDisplay textDisplay : block.textDisplays) {
                    textDisplayConsumer.accept(textDisplay);
                }
        }
    }

    public enum Flag {
        UPDATE_TEXT
    }
}
