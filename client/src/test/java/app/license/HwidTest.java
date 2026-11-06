package app.license;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HwidTest {
    @Test
    void normalizeKeepsCanonicalReadableFormat() {
        assertEquals("SMC-ABCD-EF12", Hwid.normalize(" smc-abcd-ef12 "));
        assertEquals("SMC-ABCD-EF12", Hwid.normalize("smc_abcd_ef12"));
    }

    @Test
    void currentHwidHasStablePublicShape() {
        String hwid = Hwid.current();

        assertTrue(hwid.matches("SMC(-[0-9A-F]{4}){8}"));
    }

    @Test
    void allIncludesPrimaryAndLegacyFormats() {
        assertTrue(Hwid.all().contains(Hwid.current()));
        for (String hwid : Hwid.all()) {
            assertTrue(hwid.matches("SMC(-[0-9A-F]{4}){8}"));
        }
    }
}
