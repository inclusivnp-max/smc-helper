package app.license;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseTransportSecurityTest {
    @Test
    void externalHttpIsRejectedBecauseWiresharkCanReadIt() {
        String error = LicenseTransportSecurity.validateServerUrl(URI.create("http://license.example.com"), true, false);

        assertFalse(error.isBlank());
    }

    @Test
    void localHttpCanStayEnabledForDevelopment() {
        String error = LicenseTransportSecurity.validateServerUrl(URI.create("http://127.0.0.1:8787"), true, false);

        assertTrue(error.isBlank());
    }

    @Test
    void externalHttpCanBeAllowedExplicitlyForTemporaryVpsSetup() {
        String error = LicenseTransportSecurity.validateServerUrl(URI.create("http://127.0.0.1:8787"), true, true);

        assertTrue(error.isBlank());
    }

    @Test
    void publicKeyPinCanBePastedInCommonFormats() {
        assertEquals("AABBCCDDEEFF", LicenseTransportSecurity.normalizePin("sha256/aa:bb-cc dd ee ff"));
    }
}
