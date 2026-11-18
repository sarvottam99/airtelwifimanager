package com.sarvo.routerdevices;

final class RouterDevice {
    final int oid;
    final String hostname;
    final String ip;
    final String mac;
    final boolean active;
    final String interfaceType;
    final boolean blocked;

    RouterDevice(int oid, String hostname, String ip, String mac, boolean active, String interfaceType) {
        this(oid, hostname, ip, mac, active, interfaceType, false);
    }

    RouterDevice(int oid, String hostname, String ip, String mac, boolean active,
                 String interfaceType, boolean blocked) {
        this.oid = oid;
        this.hostname = clean(hostname);
        this.ip = clean(ip);
        this.mac = clean(mac);
        this.active = active;
        this.interfaceType = clean(interfaceType);
        this.blocked = blocked;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    String displayName() {
        return hostname.isEmpty() ? "Unnamed device" : hostname;
    }

    /** Stable key for the local saved-device list. */
    String identityKey() {
        if (!mac.isEmpty()) return "mac:" + mac.toLowerCase(java.util.Locale.ROOT);
        if (!ip.isEmpty()) return "ip:" + ip + "|" + hostname.toLowerCase(java.util.Locale.ROOT);
        return "name:" + hostname.toLowerCase(java.util.Locale.ROOT);
    }

    RouterDevice withActive(boolean value) {
        return new RouterDevice(oid, hostname, ip, mac, value, interfaceType, blocked);
    }

    RouterDevice withBlocked(boolean value) {
        return new RouterDevice(oid, hostname, ip, mac, active, interfaceType, value);
    }

    boolean matches(String query) {
        if (query == null || query.trim().isEmpty()) return true;
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        return hostname.toLowerCase(java.util.Locale.ROOT).contains(q)
                || ip.toLowerCase(java.util.Locale.ROOT).contains(q)
                || mac.toLowerCase(java.util.Locale.ROOT).contains(q)
                || interfaceType.toLowerCase(java.util.Locale.ROOT).contains(q);
    }
}
