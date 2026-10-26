package cn.lanpeer;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ProtocolTest {
    @Test public void invitationRoundTrip() {
        Pairing original = new Pairing("192.168.43.1", 8765, "0123456789abcdef0123456789abcdef", "a".repeat(64));
        Pairing parsed = Pairing.parse(original.invitation());
        assertEquals(original.host, parsed.host);
        assertEquals(original.token, parsed.token);
        assertEquals(original.fingerprint, parsed.fingerprint);
    }

    @Test public void rejectsPublicLoopbackAndMalformedAddresses() {
        for (String host : new String[]{"8.8.8.8", "127.0.0.1", "169.254.1.2", "192.168.1.256", "192.168.01.1", "example.com", "172.32.0.1"}) {
            assertFalse(host, LanAddress.isPrivateV4(host));
        }
        for (String host : new String[]{"192.168.1.1", "172.16.0.1", "172.31.255.254", "10.0.0.1"}) assertTrue(host, LanAddress.isPrivateV4(host));
    }

    @Test public void rejectsUnsupportedOrAmbiguousInvitations() {
        String base = new Pairing("192.168.1.1", 8765, "12345678", "1234567890123456").invitation();
        for (String invalid : new String[]{base.replace("v=1", "v=2"), base + "&token=87654321", base.replace("lanpeer:", "https:"), base.replace("192.168.1.1", "8.8.8.8"), base + "#fragment", base.replace(":8765", ":80")}) {
            assertThrows(IllegalArgumentException.class, () -> Pairing.parse(invalid));
        }
    }

    @Test public void ignoresLetterboxAndMapsCenter() {
        assertNull(TouchGeometry.normalize(100, 400, 1200, 800, 1080, 1920));
        assertArrayEquals(new float[]{0.5f, 0.5f}, TouchGeometry.normalize(600, 400, 1200, 800, 1080, 1920), 0.001f);
        assertArrayEquals(new float[]{0f, 0f}, TouchGeometry.normalize(375, 0, 1200, 800, 1080, 1920), 0.001f);
        assertNull(TouchGeometry.normalize(0, 0, 0, 0, 0, 0));
    }

    @Test public void landscapeVideoFitsPortraitController() {
        assertNull(TouchGeometry.normalize(400, 100, 800, 1200, 1920, 1080));
        assertArrayEquals(new float[]{0.5f, 0.5f}, TouchGeometry.normalize(400, 600, 800, 1200, 1920, 1080), 0.001f);
    }

    @Test public void cropModeMapsVisibleAreaWithoutTouchingHiddenEdges() {
        assertArrayEquals(new float[]{0.5f, 0.5f}, TouchGeometry.normalizeCrop(600, 400, 1200, 800, 1080, 1920), 0.001f);
        assertTrue(TouchGeometry.normalizeCrop(600, 0, 1200, 800, 1080, 1920)[1] > 0f);
        assertTrue(TouchGeometry.normalizeCrop(600, 800, 1200, 800, 1080, 1920)[1] < 1f);
        assertNull(TouchGeometry.normalizeCrop(0, 0, 0, 0, 1080, 1920));
    }

    @Test public void excludesCellularAndVpnInvitations() {
        for (String name : new String[]{"rmnet_data3", "ccmni0", "tun0", "lo", "rndis0"}) assertFalse(LanAddress.isLanInterface(name));
        for (String name : new String[]{"wlan0", "ap0", "swlan0", "eth0", "br0", "ap_br_wlan1"}) assertTrue(LanAddress.isLanInterface(name));
    }
}
