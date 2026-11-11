package com.broadside.speedway.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.broadside.speedway.SpeedwayGame;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/** Desktop launcher. */
public class Lwjgl3Launcher {
    public static void main(String[] args) {
        if (restartOnFirstThreadIfNeeded(args)) return;
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Broadside Speedway");
        config.setWindowedMode(1280, 720);
        config.useVsync(true);
        config.setForegroundFPS(120);
        config.setBackBufferConfig(8, 8, 8, 8, 24, 0, 4); // 4x MSAA
        if (System.getProperty("broadside.hidden") != null) config.setInitialVisible(false);
        new Lwjgl3Application(new SpeedwayGame(SpeedwayGame.Debug.fromSystemProperties()), config);
    }

    /**
     * macOS requires GLFW (LWJGL3) to run on the process's first thread, which the JVM only does
     * with -XstartOnFirstThread. If we're on macOS without it (e.g. `java -jar ...`), relaunch
     * ourselves with the flag and return true. Same approach as libGDX's StartupHelper.
     */
    private static boolean restartOnFirstThreadIfNeeded(String[] args) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) return false;
        long pid = ProcessHandle.current().pid();
        if ("1".equals(System.getenv("JAVA_STARTED_ON_FIRST_THREAD_" + pid))) return false;
        if ("true".equals(System.getProperty("broadside.relaunched"))) return false; // avoid loops
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-XstartOnFirstThread");
        cmd.add("-Dbroadside.relaunched=true");
        cmd.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Lwjgl3Launcher.class.getName());
        for (String a : args) cmd.add(a);
        try {
            Process p = new ProcessBuilder(cmd).inheritIO().start();
            System.exit(p.waitFor());
        } catch (Exception e) {
            System.err.println("Could not relaunch with -XstartOnFirstThread: " + e
                + "\nRun with: java -XstartOnFirstThread -jar broadside-speedway.jar");
        }
        return true;
    }
}
