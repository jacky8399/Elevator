package com.jacky8399.elevator.utils;

import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class MathUtils {
    public static final Quaternionf NO_ROTATION = new Quaternionf();
    public static final Vector3f DEFAULT_SCALE = new Vector3f(1);
    public static final Vector3f DEFAULT_TRANSLATION = new Vector3f();
    public static final Transformation DEFAULT_TRANSFORMATION = new Transformation(DEFAULT_TRANSLATION, NO_ROTATION, DEFAULT_SCALE, NO_ROTATION);

    public static Transformation withTranslation(Transformation transformation, Vector3f translation) {
        return new Transformation(translation, transformation.getLeftRotation(), transformation.getScale(), transformation.getRightRotation());
    }

    public static Transformation translateBy(Vector3f translation) {
        return new Transformation(translation, NO_ROTATION, DEFAULT_SCALE, NO_ROTATION);
    }

    public static Transformation scaleBy(Vector3f scale) {
        return new Transformation(DEFAULT_TRANSLATION, NO_ROTATION, scale, NO_ROTATION);
    }
}
