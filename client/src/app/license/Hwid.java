package app.license;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Hwid {
    private static final String PREFIX = "SMC";

    private Hwid() {
    }

    public static String current() {
        return formatFingerprint(fingerprint(collectStableSignals()));
    }

    public static List<String> all() {
        Set<String> values = new LinkedHashSet<>();
        values.add(current());
        values.add(legacyCurrent());
        values.remove("");
        return List.copyOf(values);
    }

    static String legacyCurrent() {
        return formatFingerprint(fingerprint(collectSignals()));
    }

    public static String normalize(String value) {
        if (value == null) return "";
        return value.trim()
                .replace(" ", "")
                .replace("_", "-")
                .toUpperCase(Locale.ROOT);
    }

    static String formatFingerprint(byte[] digest) {
        String hex = HexFormat.of().formatHex(digest).toUpperCase(Locale.ROOT);
        List<String> groups = new ArrayList<>();
        for (int i = 0; i < 32; i += 4) {
            groups.add(hex.substring(i, i + 4));
        }
        return PREFIX + "-" + String.join("-", groups);
    }

    private static byte[] fingerprint(List<String> signals) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String signal : signals) {
                digest.update(signal.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static List<String> collectStableSignals() {
        List<String> signals = new ArrayList<>();
        addWindowsMachineGuid(signals);
        if (!signals.isEmpty()) return signals;

        addSystemProperty(signals, "os.name");
        addSystemProperty(signals, "os.arch");
        addEnv(signals, "PROCESSOR_IDENTIFIER");
        addEnv(signals, "PROCESSOR_ARCHITECTURE");
        addEnv(signals, "PROCESSOR_REVISION");
        addEnv(signals, "COMPUTERNAME");
        addFirstStableMac(signals);
        addHostName(signals);
        if (signals.isEmpty()) {
            signals.add("fallback:" + System.getProperty("user.home", "unknown"));
        }
        Collections.sort(signals);
        return signals;
    }

    private static List<String> collectSignals() {
        List<String> signals = new ArrayList<>();
        addSystemProperty(signals, "os.name");
        addSystemProperty(signals, "os.arch");
        addSystemProperty(signals, "os.version");
        addEnv(signals, "COMPUTERNAME");
        addEnv(signals, "PROCESSOR_IDENTIFIER");
        addEnv(signals, "PROCESSOR_ARCHITECTURE");
        addEnv(signals, "PROCESSOR_REVISION");
        addHostName(signals);
        addNetworkInterfaces(signals);
        if (signals.isEmpty()) {
            signals.add("fallback:" + System.getProperty("user.home", "unknown"));
        }
        Collections.sort(signals);
        return signals;
    }

    private static void addSystemProperty(List<String> signals, String key) {
        String value = System.getProperty(key, "");
        if (!value.isBlank()) signals.add(key + "=" + value.trim());
    }

    private static void addEnv(List<String> signals, String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) signals.add(key + "=" + value.trim());
    }

    private static void addHostName(List<String> signals) {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isBlank()) signals.add("hostname=" + host.trim());
        } catch (Exception ignored) {
        }
    }

    private static void addWindowsMachineGuid(List<String> signals) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        try {
            String guid = Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_LOCAL_MACHINE,
                    "SOFTWARE\\Microsoft\\Cryptography",
                    "MachineGuid");
            if (guid != null && !guid.isBlank()) {
                signals.add("windows-machine-guid=" + guid.trim().toLowerCase(Locale.ROOT));
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static void addNetworkInterfaces(List<String> signals) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!isUsefulInterface(nif)) continue;
                byte[] mac = nif.getHardwareAddress();
                if (mac == null || mac.length == 0) continue;
                signals.add("mac:" + nif.getName() + "="
                        + HexFormat.ofDelimiter(":").formatHex(mac).toUpperCase(Locale.ROOT));
            }
        } catch (Exception ignored) {
        }
    }

    private static void addFirstStableMac(List<String> signals) {
        List<String> macs = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!isUsefulInterface(nif)) continue;
                byte[] mac = nif.getHardwareAddress();
                if (mac == null || mac.length == 0) continue;
                macs.add(HexFormat.ofDelimiter(":").formatHex(mac).toUpperCase(Locale.ROOT));
            }
        } catch (Exception ignored) {
        }
        Collections.sort(macs);
        if (!macs.isEmpty()) signals.add("mac=" + macs.get(0));
    }

    private static boolean isUsefulInterface(NetworkInterface nif) {
        try {
            if (nif == null || !nif.isUp() || nif.isLoopback() || nif.isVirtual()) return false;
            String name = (nif.getName() + " " + nif.getDisplayName()).toLowerCase(Locale.ROOT);
            return !name.contains("docker")
                    && !name.contains("virtual")
                    && !name.contains("vmware")
                    && !name.contains("hyper-v")
                    && !name.contains("loopback");
        } catch (Exception ignored) {
            return false;
        }
    }
}
