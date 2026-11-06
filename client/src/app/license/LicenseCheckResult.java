package app.license;

public record LicenseCheckResult(
        boolean allowed,
        String message,
        String expiresAt,
        long secondsLeft
) {
    public static LicenseCheckResult allowed(String message, String expiresAt, long secondsLeft) {
        return new LicenseCheckResult(true, clean(message, "Доступ разрешен"), clean(expiresAt, ""), secondsLeft);
    }

    public static LicenseCheckResult denied(String message) {
        return new LicenseCheckResult(false, clean(message, "Доступ запрещен"), "", 0);
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
