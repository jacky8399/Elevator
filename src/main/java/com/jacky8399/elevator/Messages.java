package com.jacky8399.elevator;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

public class Messages extends TranslatableComponentRenderer<Map<String, Component>> {
    private static final Messages RENDERER = new Messages();
    @Override
    protected @NotNull Component renderTranslatable(@NotNull TranslatableComponent component, @NotNull Map<String, Component> context) {
        Component placeholder = context.get(component.key());
        if (placeholder != null) {
            var builder = Component.text();
            mergeStyle(component, builder, context);

            builder.append(placeholder);
            return optionallyRenderChildrenAppendAndBuild(component.children(), builder, context);
        } else {
            return super.renderTranslatable(component, context);
        }
    }

    public static Component renderMessage(Component message, Map<String, Component> placeholders) {
        return RENDERER.render(message, placeholders);
    }

    @Retention(RetentionPolicy.RUNTIME)
    private @interface Message {
        String value();
        String[] placeholders() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    private @interface Placeholders {
        String[] value();
    }

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    public static void reload() {
        File file = new File(Elevator.INSTANCE.getDataFolder(), "messages.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        try (var reader = new InputStreamReader(Objects.requireNonNull(Elevator.INSTANCE.getResource("messages.yml")))) {
            yaml.setDefaults(YamlConfiguration.loadConfiguration(reader));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // constants
        var constantResolverBuilder = TagResolver.builder()
                .tag("prefix", Tag.selfClosingInserting(
                        MINI_MESSAGE.deserialize(Objects.requireNonNull(yaml.getString("prefix")))));
        ConfigurationSection constants = yaml.getConfigurationSection("__CONSTANTS");
        if (constants != null) {
            for (var entry : constants.getValues(false).entrySet()) {
                try {
                    // throws IllegalArgumentException
                    //noinspection PatternValidation
                    constantResolverBuilder.tag(entry.getKey(), Tag.preProcessParsed(entry.getValue().toString()));
                } catch (IllegalArgumentException ex) {
                    Elevator.LOGGER.log(Level.WARNING, "Invalid message constant " + entry.getKey(), ex);
                }
            }
        }
        TagResolver constantResolver = constantResolverBuilder.build();

        for (Field field : Messages.class.getFields()) {
            Message annotation = field.getAnnotation(Message.class);
            if (!field.accessFlags().contains(AccessFlag.STATIC) || annotation == null) continue;

            // build a tag resolver which resolves placeholders into translatables that will be rendered later
            var tagResolver = annotation.placeholders().length != 0 ? TagResolver.caching(string -> {
                for (String available : annotation.placeholders()) {
                    if (string.equals(available)) {
                        return Tag.selfClosingInserting(Component.translatable(available));
                    }
                }
                return null;
            }) : TagResolver.empty();

            String message = yaml.getString(annotation.value());
            Component component = message == null ? Component.empty() : MINI_MESSAGE.deserialize(message, constantResolver, tagResolver);

            try {
                field.set(null, component);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to set field " + field.getName(), e);
            }
        }

        yaml.options().copyDefaults(true);
        try {
            yaml.save(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Message("error.not-in-elevator")
    public static Component msgErrorNotInElevator;

    @Message("floors.please-scan-first")
    public static Component msgNoFloors;
    @Message(value = "floors.current", placeholders = {"down", "current", "up"})
    public static Component msgCurrentFloor;
    @Message(value = "floors.selected", placeholders = {"down", "current", "up"})
    public static Component msgFloor;
    @Message("floors.no-floor")
    public static Component msgNoFloor;

    @Message(value = "floors.default-ground-floor-name", placeholders = {"floor", "floor_minus_one"})
    public static Component msgDefaultGroundFloorName;
    @Message(value = "floors.default-floor-name", placeholders = {"floor", "floor_minus_one"})
    public static Component msgDefaultFloorName;
    @Message(value = "floors.enter-floor-name", placeholders = "floor_name")
    public static Component msgEnterFloorName;

    @Message(value = "cooldown", placeholders = "cooldown")
    public static Component msgCooldown;
    @Message("maintenance")
    public static Component msgMaintenance;
    @Message("begin-maintenance")
    public static Component msgBeginMaintenance;
    @Message("end-maintenance")
    public static Component msgEndMaintenance;


    @Message(value = "scan.result", placeholders = "floors")
    public static Component msgScanResult;
    @Message(value = "scan.scanned-floor", placeholders = {"name", "y"})
    public static Component msgScannedFloor;
    @Message(value = "scan.scanned-current-floor", placeholders = {"name", "y"})
    public static Component msgScannedCurrentFloor;

    @Message("edit-cabin.instructions")
    public static Component msgEditCabinInstructions;
    @Message("edit-cabin.pos1")
    public static Component msgEditCabinPos1;
    @Message("edit-cabin.pos2")
    public static Component msgEditCabinPos2;
    @Message("edit-cabin.success")
    public static Component msgEditCabinSuccess;
    @Message("edit-cabin.failure")
    public static Component msgEditCabinFailed;

    // Utility methods
    public static Component defaultFloorName(int floor) {
        return renderMessage(floor == 0 ? msgDefaultGroundFloorName : msgDefaultFloorName,
                Map.of("floor", Component.text(floor + 1), "floor_minus_one", Component.text(floor)));
    }

    public static Component floorMessage(Component template, @Nullable Component down, @Nullable Component current, @Nullable Component up) {
        return renderMessage(template, Map.of(
                "down", down != null ? down : msgNoFloor,
                "current", current != null ? current : msgNoFloor,
                "up", up != null ? up : msgNoFloor
        ));
    }

    private static final MiniMessage PLAYER_MINI_MESSAGE = MiniMessage.builder()
            .tags(TagResolver.resolver(
                    StandardTags.color(),
                    StandardTags.decorations(),
                    StandardTags.font(),
                    StandardTags.gradient(),
                    StandardTags.rainbow(),
                    StandardTags.transition(),
                    StandardTags.reset()
            ))
            .build();

    /**
     * Parses a player-provided MiniMessage input into a component, only accepting format tags
     * @param input The input string in MiniMessage format
     * @return The parsed component
     */
    public static Component parsePlayerMiniMessage(String input) {
        return PLAYER_MINI_MESSAGE.deserialize(input);
    }
}
