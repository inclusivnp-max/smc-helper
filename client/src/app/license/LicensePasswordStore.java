package app.license;

import app.common.ProjectPaths;
import com.sun.jna.platform.win32.Crypt32Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

public final class LicensePasswordStore {
    private static final Path PATH = ProjectPaths.root().resolve("settings").resolve("license-password.bin");
    private static final byte[] DPAPI_PREFIX = "DPAPI1\n".getBytes(StandardCharsets.US_ASCII);

    private LicensePasswordStore() {
    }

    public static char[] load() {
        if (!isWindows() || !Files.exists(PATH)) return new char[0];
        try {
            byte[] stored = Files.readAllBytes(PATH);
            if (!hasPrefix(stored, DPAPI_PREFIX)) return new char[0];
            byte[] encrypted = Arrays.copyOfRange(stored, DPAPI_PREFIX.length, stored.length);
            byte[] plain = Crypt32Util.cryptUnprotectData(encrypted);
            try {
                return new String(plain, StandardCharsets.UTF_8).toCharArray();
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } catch (Exception ignored) {
            return new char[0];
        }
    }

    public static void save(char[] password) throws IOException {
        if (!isWindows()) throw new IOException("Password saving is available only on Windows DPAPI.");
        String text = password == null ? "" : new String(password);
        byte[] plain = text.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] encrypted = Crypt32Util.cryptProtectData(plain);
            byte[] stored = new byte[DPAPI_PREFIX.length + encrypted.length];
            System.arraycopy(DPAPI_PREFIX, 0, stored, 0, DPAPI_PREFIX.length);
            System.arraycopy(encrypted, 0, stored, DPAPI_PREFIX.length, encrypted.length);
            Files.createDirectories(PATH.getParent());
            Files.write(PATH, stored);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    public static void clear() {
        try {
            Files.deleteIfExists(PATH);
        } catch (IOException ignored) {
        }
    }

    private static boolean hasPrefix(byte[] value, byte[] prefix) {
        if (value == null || value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) return false;
        }
        return true;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
