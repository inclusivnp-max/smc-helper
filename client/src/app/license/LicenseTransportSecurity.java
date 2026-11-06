package app.license;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Locale;

final class LicenseTransportSecurity {
    private LicenseTransportSecurity() {
    }

    static String validateServerUrl(URI uri, boolean allowInsecureLocal, boolean allowInsecureRemote) {
        if (uri == null) return "Не задан адрес сервера лицензий.";
        String scheme = lower(uri.getScheme());
        if ("https".equals(scheme)) return "";
        if ("http".equals(scheme) && allowInsecureLocal && isLoopback(uri.getHost())) return "";
        if ("http".equals(scheme) && allowInsecureRemote) return "";
        return "Небезопасный адрес лицензии. Для HTTP на внешнем VPS включи allow.insecure.remote=true. Пароль/HWID можно прочитать в Wireshark.";
    }

    static SSLContext pinnedSslContext(String publicKeySha256) throws GeneralSecurityException {
        String pin = normalizePin(publicKeySha256);
        if (pin.isBlank()) return null;
        if (!pin.matches("[0-9A-F]{64}")) {
            throw new GeneralSecurityException("TLS public key pin must be a SHA-256 hex string");
        }

        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        X509TrustManager delegate = findX509TrustManager(factory.getTrustManagers());

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new PublicKeyPinningTrustManager(delegate, pin)}, null);
        return context;
    }

    static String normalizePin(String pin) {
        if (pin == null) return "";
        String text = pin.trim();
        int slash = text.indexOf('/');
        if (slash >= 0) text = text.substring(slash + 1);
        return text.replace(":", "")
                .replace("-", "")
                .replace(" ", "")
                .toUpperCase(Locale.ROOT);
    }

    private static X509TrustManager findX509TrustManager(TrustManager[] managers) {
        for (TrustManager manager : managers) {
            if (manager instanceof X509TrustManager x509) return x509;
        }
        throw new IllegalStateException("No default X509 trust manager is available");
    }

    private static boolean isLoopback(String host) {
        String value = lower(host);
        return "localhost".equals(value)
                || "127.0.0.1".equals(value)
                || value.startsWith("127.")
                || "::1".equals(value)
                || "[::1]".equals(value);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static final class PublicKeyPinningTrustManager implements X509TrustManager {
        private final X509TrustManager delegate;
        private final String expectedPin;

        private PublicKeyPinningTrustManager(X509TrustManager delegate, String expectedPin) {
            this.delegate = delegate;
            this.expectedPin = expectedPin;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            if (chain == null || chain.length == 0) throw new CertificateException("Server certificate chain is empty");
            String actualPin = publicKeySha256(chain[0]);
            if (!expectedPin.equals(actualPin)) {
                throw new CertificateException("License server TLS public key pin mismatch");
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }

        private String publicKeySha256(X509Certificate certificate) throws CertificateException {
            try {
                byte[] encoded = certificate.getPublicKey().getEncoded();
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded);
                return HexFormat.of().formatHex(digest).toUpperCase(Locale.ROOT);
            } catch (Exception ex) {
                throw new CertificateException("Unable to calculate public key pin", ex);
            }
        }
    }
}
