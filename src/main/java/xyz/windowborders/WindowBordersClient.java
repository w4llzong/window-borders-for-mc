package xyz.windowborders;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Keeps the game window's title bar / borders and lets you move it between monitors.
 * Uses plain GLFW calls on the window that owns the current OpenGL context, so it
 * does not depend on Minecraft's internal class names.
 */
public final class WindowBordersClient implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("windowborders");

    // ---- config (config/windowborders.properties)
    private boolean forceBorders = true;
    private int checkInterval = 20;
    private int startMonitor = 0;   // 0 = don't move on start, 1 = primary monitor, 2 = second, ...
    private int startDelay = 60;
    private int toggleKey = GLFW.GLFW_KEY_F8;
    private int nextMonitorKey = GLFW.GLFW_KEY_F9;

    // ---- state
    private int tick = 0;
    private boolean startDone = false;
    private boolean suspendForce = false; // user turned borders off on purpose
    private boolean toggleWasDown = false;
    private boolean nextWasDown = false;

    @Override
    public void onInitializeClient() {
        loadConfig();
        ClientTickEvents.END_CLIENT_TICK.register(client -> onTick());
        LOG.info("Window Borders loaded (force-borders={}, toggle={}, next-monitor={}).",
                forceBorders, toggleKey, nextMonitorKey);
    }

    // ------------------------------------------------------------------ tick

    private void onTick() {
        tick++;
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) return;

        boolean fullscreen = GLFW.glfwGetWindowMonitor(window) != 0L;

        // Keep borders on (windowed mode only)
        if (forceBorders && !suspendForce && !fullscreen && tick % Math.max(1, checkInterval) == 0) {
            if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_DECORATED) == GLFW.GLFW_FALSE) {
                GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
            }
        }

        // One-time move to the chosen monitor shortly after start
        if (!startDone && tick >= startDelay) {
            startDone = true;
            if (startMonitor > 0 && !fullscreen) moveToMonitor(window, startMonitor - 1);
        }

        // Hotkeys (rising edge)
        boolean toggleDown = toggleKey > 0 && GLFW.glfwGetKey(window, toggleKey) == GLFW.GLFW_PRESS;
        if (toggleDown && !toggleWasDown && !fullscreen) {
            boolean decorated = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_DECORATED) == GLFW.GLFW_TRUE;
            GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, decorated ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE);
            suspendForce = decorated; // if we just removed borders, stop re-adding them
        }
        toggleWasDown = toggleDown;

        boolean nextDown = nextMonitorKey > 0 && GLFW.glfwGetKey(window, nextMonitorKey) == GLFW.GLFW_PRESS;
        if (nextDown && !nextWasDown && !fullscreen) {
            PointerBuffer mons = GLFW.glfwGetMonitors();
            if (mons != null && mons.limit() > 1) {
                int cur = currentMonitorIndex(window, mons);
                moveToMonitor(window, (cur + 1) % mons.limit());
            }
        }
        nextWasDown = nextDown;
    }

    // ------------------------------------------------------------------ monitor helpers

    private static int currentMonitorIndex(long window, PointerBuffer mons) {
        int[] wx = new int[1], wy = new int[1], ww = new int[1], wh = new int[1];
        GLFW.glfwGetWindowPos(window, wx, wy);
        GLFW.glfwGetWindowSize(window, ww, wh);
        int cx = wx[0] + ww[0] / 2;
        int cy = wy[0] + wh[0] / 2;

        for (int i = 0; i < mons.limit(); i++) {
            long mon = mons.get(i);
            int[] mx = new int[1], my = new int[1];
            GLFW.glfwGetMonitorPos(mon, mx, my);
            GLFWVidMode vm = GLFW.glfwGetVideoMode(mon);
            if (vm == null) continue;
            if (cx >= mx[0] && cx < mx[0] + vm.width() && cy >= my[0] && cy < my[0] + vm.height()) return i;
        }
        return 0;
    }

    /** Centers the window on the given monitor (0 = primary), keeping the title bar on screen. */
    private static void moveToMonitor(long window, int index) {
        PointerBuffer mons = GLFW.glfwGetMonitors();
        if (mons == null || mons.limit() == 0) return;
        if (index < 0 || index >= mons.limit()) index = 0;

        long mon = mons.get(index);
        int[] mx = new int[1], my = new int[1];
        GLFW.glfwGetMonitorPos(mon, mx, my);
        GLFWVidMode vm = GLFW.glfwGetVideoMode(mon);
        if (vm == null) return;

        int[] ww = new int[1], wh = new int[1];
        GLFW.glfwGetWindowSize(window, ww, wh);
        int[] fl = new int[1], ft = new int[1], fr = new int[1], fb = new int[1];
        GLFW.glfwGetWindowFrameSize(window, fl, ft, fr, fb);

        int x = mx[0] + Math.max(0, (vm.width() - ww[0]) / 2);
        int y = my[0] + Math.max(ft[0], (vm.height() - wh[0]) / 2);
        GLFW.glfwSetWindowPos(window, x, y);
    }

    // ------------------------------------------------------------------ config

    private static final String DEFAULT_CONFIG = """
            # Window Borders config
            # Keep the window's title bar / borders on (windowed mode). true or false
            force-borders=true
            # How often (ticks, 20 = 1 second) to re-check the borders
            check-interval-ticks=20
            # Move the window to a monitor shortly after the game starts.
            # 0 = don't move, 1 = primary monitor, 2 = second monitor, ...
            start-monitor=0
            start-delay-ticks=60
            # Hotkeys (F1-F12, or NONE to disable)
            # toggle-borders-key: turn the borders on/off
            toggle-borders-key=F8
            # next-monitor-key: send the window to the next monitor (centered)
            next-monitor-key=F9
            """;

    private void loadConfig() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("windowborders.properties");
        Properties p = new Properties();
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                }
            } else {
                Files.writeString(file, DEFAULT_CONFIG);
            }
        } catch (IOException e) {
            LOG.warn("Could not read/write config: {}", e.getMessage());
        }
        forceBorders = Boolean.parseBoolean(p.getProperty("force-borders", "true").trim());
        checkInterval = intOr(p.getProperty("check-interval-ticks"), 20);
        startMonitor = intOr(p.getProperty("start-monitor"), 0);
        startDelay = intOr(p.getProperty("start-delay-ticks"), 60);
        toggleKey = parseKey(p.getProperty("toggle-borders-key", "F8"));
        nextMonitorKey = parseKey(p.getProperty("next-monitor-key", "F9"));
    }

    private static int intOr(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int parseKey(String s) {
        if (s == null) return -1;
        String k = s.trim().toUpperCase(Locale.ROOT);
        if (k.matches("F([1-9]|1[0-2])")) {
            return GLFW.GLFW_KEY_F1 + Integer.parseInt(k.substring(1)) - 1;
        }
        return -1; // NONE / invalid = disabled
    }
}
