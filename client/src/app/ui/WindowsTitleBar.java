package app.ui;

import app.Colors;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WindowsTitleBar {
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19;
    private static final int DWMWA_BORDER_COLOR = 34;
    private static final int DWMWA_CAPTION_COLOR = 35;
    private static final int DWMWA_TEXT_COLOR = 36;
    private static volatile boolean appThemeSyncInstalled = false;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "SMC-DWM-TitleBar");
        thread.setDaemon(true);
        return thread;
    });

    private WindowsTitleBar() {
    }

    public static void installAppThemeSync() {
        if (appThemeSyncInstalled) return;
        synchronized (WindowsTitleBar.class) {
            if (appThemeSyncInstalled) return;
            Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
                if (!(event instanceof WindowEvent windowEvent)) return;
                if (windowEvent.getID() != WindowEvent.WINDOW_OPENED) return;
                Window window = windowEvent.getWindow();
                if (window == null) return;
                EventQueue.invokeLater(() -> applyAppTheme(window));
            }, AWTEvent.WINDOW_EVENT_MASK);
            appThemeSyncInstalled = true;
        }
        syncAppThemeForOpenWindows();
    }

    public static void syncAppThemeForOpenWindows() {
        for (Window window : Window.getWindows()) {
            applyAppTheme(window);
        }
    }

    public static void applyAppTheme(Window window) {
        apply(window, Colors.PANEL_BG, Colors.FG, Colors.GRID);
    }

    public static void apply(Window window, Color caption, Color text, Color border) {
        if (window == null || !window.isDisplayable()) return;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        HWND hwnd = hwnd(window);
        if (hwnd == null) return;
        int captionColor = colorRef(caption);
        int textColor = colorRef(text);
        int borderColor = colorRef(border);
        int darkMode = luminance(caption) < 130 ? 1 : 0;
        EXECUTOR.execute(() -> applyDwm(hwnd, captionColor, textColor, borderColor, darkMode));
    }

    private static HWND hwnd(Window window) {
        try {
            Pointer pointer = Native.getComponentPointer(window);
            if (pointer == null || Pointer.nativeValue(pointer) == 0L) return null;
            return new HWND(pointer);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void applyDwm(HWND hwnd, int caption, int text, int border, int dark) {
        setDwmInt(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, dark);
        setDwmInt(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, dark);
        setDwmInt(hwnd, DWMWA_CAPTION_COLOR, caption);
        setDwmInt(hwnd, DWMWA_TEXT_COLOR, text);
        setDwmInt(hwnd, DWMWA_BORDER_COLOR, border);
    }

    private static void setDwmInt(HWND hwnd, int attribute, int value) {
        try {
            DwmApi.INSTANCE.DwmSetWindowAttribute(hwnd, attribute, new int[]{value}, Integer.BYTES);
        } catch (Throwable ignored) {
            // DWM attributes differ by Windows build; unsupported attributes are harmless.
        }
    }

    private static int colorRef(Color color) {
        Color c = color == null ? Color.BLACK : color;
        return (c.getBlue() << 16) | (c.getGreen() << 8) | c.getRed();
    }

    private static int luminance(Color color) {
        Color c = color == null ? Color.BLACK : color;
        return (int) Math.round(c.getRed() * 0.2126 + c.getGreen() * 0.7152 + c.getBlue() * 0.0722);
    }

    private interface DwmApi extends StdCallLibrary {
        DwmApi INSTANCE = Native.load("dwmapi", DwmApi.class, W32APIOptions.DEFAULT_OPTIONS);

        HRESULT DwmSetWindowAttribute(HWND hwnd, int attribute, int[] value, int valueSize);
    }
}
