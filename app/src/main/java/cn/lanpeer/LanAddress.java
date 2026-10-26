package cn.lanpeer;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LanAddress {
    private LanAddress() {}

    public static boolean isPrivateV4(String address) {
        if (address == null || !address.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        String[] parts = address.split("\\.");
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            n[i] = Integer.parseInt(parts[i]);
            if (n[i] > 255 || (parts[i].length() > 1 && parts[i].startsWith("0"))) return false;
        }
        return n[0] == 10 || (n[0] == 172 && n[1] >= 16 && n[1] <= 31)
                || (n[0] == 192 && n[1] == 168);
    }

    public static List<String> local() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || !isLanInterface(network.getName())) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (isPrivateV4(address.getHostAddress())) result.add(address.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return result;
    }

    public static boolean isLanInterface(String name) {
        // Cellular operators also allocate 10.x addresses; those must never appear as Wi-Fi invitations.
        String value = name.toLowerCase(java.util.Locale.ROOT);
        return value.startsWith("wlan") || value.startsWith("wifi") || value.startsWith("swlan")
                || value.startsWith("ap") || value.startsWith("softap") || value.startsWith("eth")
                || value.startsWith("br");
    }
}
