package app.ui;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.Window;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public final class AppIcons {
    private static final int[] WINDOW_ICON_SIZES = {16, 24, 32, 48, 64, 128, 256, 512};

    private AppIcons() {
    }

    public static void install(Window window) {
        if (window == null) return;
        List<Image> icons = loadWindowIcons();
        if (!icons.isEmpty()) {
            window.setIconImages(icons);
        }
    }

    public static void installTaskbarIcon() {
        if (!Taskbar.isTaskbarSupported()) return;
        try {
            Image icon = loadLargestIcon();
            if (icon != null && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().setIconImage(icon);
            }
        } catch (RuntimeException ignored) {
            // Some Windows shell integrations reject taskbar icon changes for plain JVM launches.
        }
    }

    private static List<Image> loadWindowIcons() {
        List<Image> icons = new ArrayList<>();
        for (int size : WINDOW_ICON_SIZES) {
            Image icon = loadIcon(size);
            if (icon != null) icons.add(icon);
        }
        return icons;
    }

    private static Image loadLargestIcon() {
        for (int i = WINDOW_ICON_SIZES.length - 1; i >= 0; i--) {
            Image icon = loadIcon(WINDOW_ICON_SIZES[i]);
            if (icon != null) return icon;
        }
        return null;
    }

    private static Image loadIcon(int size) {
        String path = "app/icons/app-icon-" + size + ".png";
        URL url = AppIcons.class.getClassLoader().getResource(path);
        if (url == null) return null;
        try {
            return ImageIO.read(url);
        } catch (IOException ignored) {
            return null;
        }
    }
}
