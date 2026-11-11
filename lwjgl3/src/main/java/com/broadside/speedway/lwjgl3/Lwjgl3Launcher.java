package com.broadside.speedway.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.broadside.speedway.SpeedwayGame;

/** Desktop launcher. */
public class Lwjgl3Launcher {
    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Broadside Speedway");
        config.setWindowedMode(1280, 720);
        config.useVsync(true);
        config.setForegroundFPS(120);
        config.setBackBufferConfig(8, 8, 8, 8, 24, 0, 4); // 4x MSAA
        if (System.getProperty("broadside.hidden") != null) config.setInitialVisible(false);
        new Lwjgl3Application(new SpeedwayGame(SpeedwayGame.Debug.fromSystemProperties()), config);
    }
}
