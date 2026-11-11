package com.broadside.speedway;

/** Physics tuning constants (tuned with the headless sim, see lwjgl3 SimMain). */
public final class Phys {
    private Phys() {}

    public static final float DT = 1f / 120f;
    public static final float GRAVITY = 9.81f;
    public static final float MASS = 165f;            // bike + rider (kg)
    public static final float POWER = 52000f;         // W
    public static final float MAX_DRIVE = 1900f;      // N traction-limited launch
    public static final float DRAG = 1.45f;           // N/(m/s)^2
    public static final float ROLLING = 70f;          // N
    public static final float ENGINE_BRAKE = 650f;    // N off throttle (no brakes!)
    public static final float GRIP = 1.55f;           // friction-circle coefficient
    public static final float THROTTLE_GRIP_LOSS = 0.18f;
    public static final float SLIP_SPEED = 1.6f;      // m/s lateral slip for ~76% tyre force
    public static final float MAX_YAW_RATE = 2.1f;    // rad/s
    public static final float RIDE_HEIGHT = 0.62f;
    public static final float FENCE_IMPACT_CRASH = 12.5f;
    public static final float BIKE_IMPACT_CRASH = 7.5f;
    public static final float OVERSLIDE_ANGLE = 1.3f;
    public static final float REMOUNT_TIME = 3.2f;
    // Classic-mode rider instinct
    public static final float ASSIST_DRIFT = 0.8f;
    public static final float ASSIST_GAIN = 3.0f;
    // Assisted steering (player default)
    public static final float ASSIST_BASE_DRIFT = 0.22f;
    public static final float ASSIST_THROTTLE_DRIFT = 0.33f;
    public static final float ASSIST_SPEED_DRIFT = 0.4f;
    public static final float ASSIST_HOLD_GAIN = 4.5f;
    public static final float ASSIST_FEATHER = 0.75f;
    public static final float FEATHER_SPEED = 17f;
    public static final float ASSIST_GRIP_BOOST = 1.08f;
    public static final float INFIELD_DRAG = 120f;    // N/(m/s) on the grass

    // Collision groups
    public static final short G_GROUND = 1, G_FENCE = 2, G_BIKE = 4, G_RAGDOLL = 8;
    public static final short MASK_BIKE = G_GROUND | G_FENCE | G_BIKE | G_RAGDOLL;
    public static final short MASK_BIKE_NO_RAGDOLL = G_GROUND | G_FENCE | G_BIKE;

    // userValue tags on collision objects
    public static final int TAG_GROUND = 1, TAG_FENCE = 1000, TAG_BIKE = 2000, TAG_RAGDOLL = 3000;
}
