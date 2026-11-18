#!/usr/bin/env python3
"""Apply router block/unblock support to the Network Scanner Airtel XStream app.
Run from the repository root: python apply_block_unblock.py
The script is deliberately guarded: it aborts when expected source anchors differ.
"""
from pathlib import Path
import re
import sys

ROOT = Path.cwd()


def read(rel):
    p = ROOT / rel
    if not p.is_file():
        raise SystemExit(f"Missing expected file: {rel}")
    return p.read_text(encoding="utf-8")


def write(rel, value):
    (ROOT / rel).write_text(value, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one anchor for {label}; found {count}. No further changes applied to that file.")
    return text.replace(old, new, 1)

# ---------------------------------------------------------------------------
# RouterDevice: add router-confirmed blocked state, while keeping the old
# six-argument constructor compatible with any existing call sites.
# ---------------------------------------------------------------------------
router_device = '''package com.sarvo.routerdevices;

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
'''
write("app/src/main/java/com/sarvo/routerdevices/RouterDevice.java", router_device)

# ---------------------------------------------------------------------------
# Persist router-confirmed blocked status. Old JSON records default to false.
# ---------------------------------------------------------------------------
store_path = "app/src/main/java/com/sarvo/routerdevices/SavedDevicesStore.java"
s = read(store_path)
s = replace_once(
    s,
    '                        item.optString("interface", "")\n                ));',
    '                        item.optString("interface", ""),\n                        item.optBoolean("blocked", false)\n                ));',
    "load blocked status",
)
s = replace_once(
    s,
    '                item.put("interface", device.interfaceType);\n                array.put(item);',
    '                item.put("interface", device.interfaceType);\n                item.put("blocked", device.blocked);\n                array.put(item);',
    "save blocked status",
)
store_update = """    /** Update router-confirmed access state even when the device is currently offline. */
    static synchronized List<RouterDevice> updateBlocked(Context context, String mac, boolean blocked) {
        String target = mac == null ? "" : mac.trim().replace('-', ':').toLowerCase(java.util.Locale.ROOT);
        List<RouterDevice> devices = load(context);
        for (int i = 0; i < devices.size(); i++) {
            RouterDevice device = devices.get(i);
            String existing = device.mac.trim().replace('-', ':').toLowerCase(java.util.Locale.ROOT);
            if (!target.isEmpty() && target.equals(existing)) {
                devices.set(i, device.withBlocked(blocked));
            }
        }
        sort(devices);
        save(context, devices);
        return devices;
    }

"""
s = replace_once(s, '    private static void sort(List<RouterDevice> devices) {', store_update + '    private static void sort(List<RouterDevice> devices) {', "saved device blocked-state update")
write(store_path, s)

# ---------------------------------------------------------------------------
# RouterClient: implement the documented Nokia G-2425G-A parental-control
# operations and derive blocked state from router data on every scan.
# ---------------------------------------------------------------------------
client_path = "app/src/main/java/com/sarvo/routerdevices/RouterClient.java"
s = read(client_path)
s = replace_once(
    s,
    'import java.util.List;\nimport java.util.regex.Matcher;',
    'import java.util.List;\nimport java.util.Locale;\nimport java.util.Set;\nimport java.util.HashSet;\nimport java.util.regex.Matcher;',
    "RouterClient imports",
)
s = replace_once(
    s,
    '/** Read-only client for the Nokia G-2425G-A web interface.\n * Protocol is based on the public airtel-router-api implementation.\n * It only logs in and reads /parental_control.cgi; it never changes router settings.\n */',
    '/** Client for the Nokia G-2425G-A web interface.\n * Login and parental-control protocol are based on the public MIT-licensed\n * airtel-router-api project. Device actions are limited to router parental control.\n */',
    "RouterClient class documentation",
)
s = replace_once(
    s,
    '        Matcher device = DEVICE_PATTERN.matcher(array.group(1));\n        List<RouterDevice> result = new ArrayList<>();',
    '        Set<String> blockedMacs = parseBlockedMacs(html);\n        Matcher device = DEVICE_PATTERN.matcher(array.group(1));\n        List<RouterDevice> result = new ArrayList<>();',
    "parse router blocked devices",
)
s = replace_once(
    s,
    '                    "1".equals(device.group(5)),\n                    decodeJsString(device.group(6))));',
    '                    "1".equals(device.group(5)),\n                    decodeJsString(device.group(6)),\n                    blockedMacs.contains(normalizeMacAddress(device.group(4)))));',
    "attach blocked state to devices",
)
helpers = r'''
    /** Fetch Parental Control data and recover once if the router session expired. */
    private String getControlPage() throws Exception {
        String html = get("/parental_control.cgi");
        if (looksLikeLoginPage(html)) {
            authenticated = false;
            cookieManager.getCookieStore().removeAll();
            LoginParams params = parseLoginParams(html);
            login(params);
            html = get("/parental_control.cgi");
        }
        if (looksLikeLoginPage(html)) throw new IOException("Router session could not be restored. Log in again and retry.");
        return html;
    }

    /** Block a device's internet access through router Parental Control. */
    boolean blockDevice(String macAddress) throws Exception {
        String mac = normalizeMacAddress(macAddress);
        if (!isValidMacAddress(mac)) throw new IOException("A valid MAC address is required to block a device.");

        String html = getControlPage();
        List<ParentalGroup> groups = parseParentalGroups(html);
        if (groups != null) {
            if (groups.isEmpty()) throw new IOException("The router's Parental Control data was present but could not be parsed; no change was made.");
            blockNewFirmware(mac, html, groups);
        } else {
            blockOldFirmware(mac, html);
        }
        return verifyBlockedState(mac, true);
    }

    /** Restore a device's internet access through router Parental Control. */
    boolean unblockDevice(String macAddress) throws Exception {
        String mac = normalizeMacAddress(macAddress);
        if (!isValidMacAddress(mac)) throw new IOException("A valid MAC address is required to unblock a device.");

        String html = getControlPage();
        List<ParentalGroup> groups = parseParentalGroups(html);
        if (groups != null) {
            ParentalGroup home = null;
            ParentalGroup source = null;
            int deviceOid = -1;
            for (ParentalGroup group : groups) {
                if (group.homeGroup) home = group;
                if (isBlockedGroup(group)) {
                    for (ParentalDevice entry : group.devices) {
                        if (normalizeMacAddress(entry.mac).equals(mac)) {
                            source = group;
                            deviceOid = entry.oid;
                            break;
                        }
                    }
                }
                if (source != null) break;
            }
            if (source == null) {
                // The device is not in a non-home group. Verify the router's view
                // before reporting success rather than trusting local state.
                return verifyBlockedState(mac, false);
            }
            if (home == null) throw new IOException("The router did not report its Home group; refusing to move the device.");
            String data = "move_id=" + home.oid + "&device_id=" + deviceOid + "&group_id=" + source.oid;
            requirePostSuccess("/parental_control.cgi?move_device_N", data, "Router rejected the unblock request.");
        } else {
            OldAccessConfig config = parseOldAccessConfig(html);
            if (config == null) throw new IOException("This router firmware's parental-control format is not recognised.");
            OldPolicy match = null;
            for (OldPolicy policy : config.policies) {
                if (policy.enabled && policy.macs.contains(mac)) {
                    if (policy.macs.size() != 1) {
                        throw new IOException("This older router rule contains multiple devices. It was not deleted to avoid unblocking other devices.");
                    }
                    match = policy;
                    break;
                }
            }
            if (match == null) return verifyBlockedState(mac, false);
            requirePostSuccess("/parental_control.cgi?del_pc_rule", "oid=" + match.oid,
                    "Router rejected the unblock request.");
        }
        return verifyBlockedState(mac, false);
    }

    private void blockNewFirmware(String mac, String html, List<ParentalGroup> groups) throws Exception {
        ParentalGroup blocked = null;
        for (ParentalGroup group : groups) {
            if (!group.homeGroup && group.name.equalsIgnoreCase("blocked")) {
                blocked = group;
                break;
            }
        }
        if (blocked == null) {
            for (ParentalGroup group : groups) {
                if (!group.homeGroup && group.accessInternet == 0) {
                    blocked = group;
                    break;
                }
            }
        }

        if (blocked == null) {
            requirePostSuccess("/parental_control.cgi?add_group_N", "group_name_input=Blocked",
                    "Router could not create the Blocked parental-control group.");
            html = getControlPage();
            groups = parseParentalGroups(html);
            if (groups == null) throw new IOException("Router did not return parental-control groups after creating Blocked.");
            for (ParentalGroup group : groups) {
                if (!group.homeGroup && group.name.equalsIgnoreCase("blocked")) {
                    blocked = group;
                    break;
                }
            }
        }
        if (blocked == null) throw new IOException("Could not locate the router's Blocked group.");

        if (blocked.accessInternet != 0) {
            requirePostSuccess("/parental_control.cgi?chg_internet_N",
                    "enable=0&group_id=" + blocked.oid,
                    "Router could not disable internet access for the Blocked group.");
            html = getControlPage();
            groups = parseParentalGroups(html);
            if (groups == null) throw new IOException("Router did not return parental-control groups after updating Blocked.");
            for (ParentalGroup group : groups) {
                if (group.oid == blocked.oid) {
                    blocked = group;
                    break;
                }
            }
            if (blocked.accessInternet != 0) throw new IOException("Router did not apply the Blocked group's internet restriction.");
        }

        for (ParentalDevice entry : blocked.devices) {
            if (normalizeMacAddress(entry.mac).equals(mac)) return;
        }

        ParentalGroup source = null;
        ParentalDevice sourceDevice = null;
        for (ParentalGroup group : groups) {
            for (ParentalDevice entry : group.devices) {
                if (normalizeMacAddress(entry.mac).equals(mac)) {
                    source = group;
                    sourceDevice = entry;
                    break;
                }
            }
            if (sourceDevice != null) break;
        }

        if (sourceDevice != null && source.oid != blocked.oid) {
            String data = "move_id=" + blocked.oid + "&device_id=" + sourceDevice.oid + "&group_id=" + source.oid;
            requirePostSuccess("/parental_control.cgi?move_device_N", data,
                    "Router rejected moving the device into the Blocked group.");
        } else {
            String data = "group_id_device=" + blocked.oid + "&add_device_mac=" + formEncode(mac);
            requirePostSuccess("/parental_control.cgi?add_device_N", data,
                    "Router rejected adding the device to the Blocked group.");
        }
    }

    private void blockOldFirmware(String mac, String html) throws Exception {
        OldAccessConfig config = parseOldAccessConfig(html);
        if (config == null) throw new IOException("This router firmware's parental-control format is not recognised.");
        if (!config.enabled) {
            requirePostSuccess("/parental_control.cgi?enable_pc", "Enable=1",
                    "Router could not enable Parental Control.");
            html = getControlPage();
            config = parseOldAccessConfig(html);
            if (config == null || !config.enabled) throw new IOException("Router did not enable Parental Control.");
        }
        for (OldPolicy policy : config.policies) {
            if (policy.enabled && policy.macs.contains(mac)) return;
        }
        String policyName = "Block_" + mac.replace(":", "");
        String data = "PolicyEnable=1&oid=0&PolicyName=" + formEncode(policyName)
                + "&addMAC=" + formEncode(mac)
                + "&addIP=&addUrlAddress=&addPortNumber="
                + "&StartTime=00%3A00&EndTime=23%3A59&DayOfWeek=0%2C1%2C2%2C3%2C4%2C5%2C6";
        requirePostSuccess("/parental_control.cgi?add", data, "Router rejected the device-blocking rule.");
    }

    private void requirePostSuccess(String path, String postData, String message) throws Exception {
        int status = encryptedPost(path, postData);
        if (status < 200 || status >= 300) throw new IOException(message + " (HTTP " + status + ").");
    }

    /** Mirrors the router UI's encrypted AJAX form submissions. */
    private int encryptedPost(String path, String postData) throws Exception {
        if (!authenticated || publicKeyPem == null) throw new IOException("Router session expired. Scan again and retry.");
        String payload = postData;
        if (!payload.contains("csrf_token=") && csrfToken != null && !csrfToken.isEmpty()) {
            payload += "&csrf_token=" + formEncode(csrfToken);
        }
        String encrypted = encryptPostData(publicKeyPem, payload);
        HttpURLConnection connection = open(path, "POST");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        connection.setRequestProperty("Referer", baseUrl + "/parental_control.cgi");
        writeBody(connection, encrypted);
        int status = connection.getResponseCode();
        String body = readResponse(connection, status);
        connection.disconnect();
        if (body != null && body.contains("Errorinfo")) {
            authenticated = false;
            throw new IOException("Router rejected the parental-control request (session or permission error).");
        }
        return status;
    }

    private boolean verifyBlockedState(String mac, boolean expectedBlocked) throws Exception {
        String freshHtml = getControlPage();
        Set<String> blocked = parseBlockedMacs(freshHtml);
        boolean actual = blocked.contains(mac);
        if (actual != expectedBlocked) {
            throw new IOException(expectedBlocked
                    ? "The router did not confirm that this device is blocked. Refresh the device list and check the router's Parental Control page."
                    : "The router did not confirm that this device is unblocked. Refresh the device list and check the router's Parental Control page.");
        }
        return true;
    }

    private static boolean isBlockedGroup(ParentalGroup group) {
        return !group.homeGroup && (group.accessInternet == 0 || group.name.equalsIgnoreCase("blocked"));
    }

    private static Set<String> parseBlockedMacs(String html) {
        Set<String> result = new HashSet<>();
        List<ParentalGroup> groups = parseParentalGroups(html);
        if (groups != null) {
            for (ParentalGroup group : groups) {
                if (group.accessInternet == 0 || (!group.homeGroup && group.name.equalsIgnoreCase("blocked"))) {
                    for (ParentalDevice device : group.devices) {
                        String mac = normalizeMacAddress(device.mac);
                        if (isValidMacAddress(mac)) result.add(mac);
                    }
                }
            }
            return result;
        }
        OldAccessConfig config = parseOldAccessConfig(html);
        if (config != null && config.enabled) {
            for (OldPolicy policy : config.policies) {
                if (policy.enabled) {
                    for (String mac : policy.macs) result.add(mac);
                }
            }
        }
        return result;
    }

    private static List<ParentalGroup> parseParentalGroups(String html) {
        if (html == null || !html.contains("parentalCtrlList")) return null;
        Pattern groupPattern = Pattern.compile(
                "\\{\\\"_oid\\\":(\\d+),\\s*Name:'([^']*)',\\s*HomeGroup:(\\d),\\s*AccessInternet:(\\d).*?\\\"Device\\\":\\[(.*?)\\]\\s*\\}",
                Pattern.DOTALL);
        Pattern devicePattern = Pattern.compile("\\{\\\"_oid\\\":(\\d+),\\s*MACAddress:'([^']*)'");
        Matcher gm = groupPattern.matcher(html);
        List<ParentalGroup> result = new ArrayList<>();
        while (gm.find()) {
            List<ParentalDevice> devices = new ArrayList<>();
            Matcher dm = devicePattern.matcher(gm.group(5));
            while (dm.find()) {
                try {
                    devices.add(new ParentalDevice(Integer.parseInt(dm.group(1)), dm.group(2)));
                } catch (NumberFormatException ignored) { }
            }
            try {
                result.add(new ParentalGroup(Integer.parseInt(gm.group(1)), gm.group(2),
                        "1".equals(gm.group(3)), Integer.parseInt(gm.group(4)), devices));
            } catch (NumberFormatException ignored) { }
        }
        return result;
    }

    private static OldAccessConfig parseOldAccessConfig(String html) {
        if (html == null) return null;
        Matcher configMatcher = Pattern.compile("var\\s+pc_config\\s*=\\s*\\{(.*?)\\};", Pattern.DOTALL).matcher(html);
        if (!configMatcher.find()) return null;
        String raw = configMatcher.group(1);
        Matcher enableMatcher = Pattern.compile("Enable:(\\d)").matcher(raw);
        boolean enabled = enableMatcher.find() && "1".equals(enableMatcher.group(1));
        Pattern policyPattern = Pattern.compile(
                "\\{_oid:(\\d+),\\s*PolicyEnable:(\\d),\\s*PolicyName:'([^']*)',\\s*"
                        + "MACDeviceNumberOfEntries:(\\d+),\\s*IPDeviceNumberOfEntries:(\\d+),\\s*"
                        + "URLFilterNumberOfEntries:(\\d+),\\s*MAC:\\[(.*?)\\],\\s*IP:\\[(.*?)\\],\\s*"
                        + "URL:\\[(.*?)\\],\\s*StartTime:'([^']*)',\\s*EndTime:'([^']*)',\\s*DayOfWeek:'([^']*)'",
                Pattern.DOTALL);
        Pattern macPattern = Pattern.compile("\\{_oid:(\\d+),\\s*SourceMAC:'([^']*)'\\s*\\}");
        Matcher pm = policyPattern.matcher(raw);
        List<OldPolicy> policies = new ArrayList<>();
        while (pm.find()) {
            List<String> macs = new ArrayList<>();
            Matcher mm = macPattern.matcher(pm.group(7));
            while (mm.find()) {
                String mac = normalizeMacAddress(mm.group(2));
                if (isValidMacAddress(mac)) macs.add(mac);
            }
            try {
                policies.add(new OldPolicy(Integer.parseInt(pm.group(1)), "1".equals(pm.group(2)), macs));
            } catch (NumberFormatException ignored) { }
        }
        return new OldAccessConfig(enabled, policies);
    }

    private static String normalizeMacAddress(String value) {
        if (value == null) return "";
        return value.trim().replace('-', ':').toLowerCase(Locale.ROOT);
    }

    private static boolean isValidMacAddress(String value) {
        return value != null && value.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}");
    }

    private static String formEncode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static final class ParentalGroup {
        final int oid;
        final String name;
        final boolean homeGroup;
        final int accessInternet;
        final List<ParentalDevice> devices;
        ParentalGroup(int oid, String name, boolean homeGroup, int accessInternet, List<ParentalDevice> devices) {
            this.oid = oid; this.name = name; this.homeGroup = homeGroup;
            this.accessInternet = accessInternet; this.devices = devices;
        }
    }

    private static final class ParentalDevice {
        final int oid;
        final String mac;
        ParentalDevice(int oid, String mac) { this.oid = oid; this.mac = mac; }
    }

    private static final class OldPolicy {
        final int oid;
        final boolean enabled;
        final List<String> macs;
        OldPolicy(int oid, boolean enabled, List<String> macs) {
            this.oid = oid; this.enabled = enabled; this.macs = macs;
        }
    }

    private static final class OldAccessConfig {
        final boolean enabled;
        final List<OldPolicy> policies;
        OldAccessConfig(boolean enabled, List<OldPolicy> policies) {
            this.enabled = enabled; this.policies = policies;
        }
    }

'''
anchor = '    private static String decodeJsString(String value) {'
s = replace_once(s, anchor, helpers + anchor, "insert parental-control implementation")
write(client_path, s)

# ---------------------------------------------------------------------------
# MainActivity: add confirmation and asynchronous router actions to both card
# lists. The card state is refreshed from the router before showing success.
# ---------------------------------------------------------------------------
main_path = "app/src/main/java/com/sarvo/routerdevices/MainActivity.java"
s = read(main_path)
s = replace_once(
    s,
    'import android.app.Activity;\n',
    'import android.app.Activity;\nimport android.app.AlertDialog;\n',
    "AlertDialog import",
)
s = replace_once(
    s,
    '    private boolean connected = false;\n    private boolean scanning = false;\n',
    '    private boolean connected = false;\n    private boolean scanning = false;\n    private boolean deviceActionRunning = false;\n',
    "device action state",
)
s = replace_once(
    s,
    '        TextView note = text("Read-only access. This app only reads the router\'s device list; it cannot block devices or change Wi-Fi settings.", 12, MUTED, false);',
    '        TextView note = text("Block and unblock controls change internet access through the router\'s Parental Control. A blocked device may remain connected to Wi-Fi. Do not block the phone running this app.", 12, MUTED, false);',
    "update app access-control note",
)
s = replace_once(
    s,
    '        scanButton.setEnabled(connected && !scanning);',
    '        scanButton.setEnabled(connected && !scanning && !deviceActionRunning);',
    "disable scan during device action",
)
activity_methods = r'''
    private void confirmDeviceAction(RouterDevice device) {
        if (!connected || routerClient == null || deviceActionRunning || scanning) return;
        if (device.mac == null || !device.mac.matches("(?i)[0-9a-f]{2}([:-][0-9a-f]{2}){5}")) {
            Toast.makeText(this, "This device has no valid MAC address; router blocking is unavailable.", Toast.LENGTH_LONG).show();
            return;
        }
        boolean block = !device.blocked;
        String title = block ? "Block internet access?" : "Unblock device?";
        String message = block
                ? "This will restrict this device's internet access using the router's Parental Control. It may stay connected to Wi-Fi. Do not block the phone running this app, or you may lose access to the router. Continue?"
                : "This will restore this device to the router's Home group and allow internet access again. Continue?";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(block ? "Block" : "Unblock", (dialog, which) -> changeDeviceAccess(device, block))
                .show();
    }

    private void changeDeviceAccess(RouterDevice device, boolean block) {
        if (!connected || routerClient == null || deviceActionRunning || scanning) return;
        RouterClient client = routerClient;
        deviceActionRunning = true;
        updateScanButton();
        renderDevices();

        executor.execute(() -> {
            List<RouterDevice> refreshed = null;
            List<RouterDevice> merged = null;
            Exception failure = null;
            try {
                boolean changed = block ? client.blockDevice(device.mac) : client.unblockDevice(device.mac);
                if (!changed) throw new java.io.IOException("The router did not confirm the requested change.");
                refreshed = client.connectAndReadDevices();
                merged = SavedDevicesStore.merge(this, refreshed);
                merged = SavedDevicesStore.updateBlocked(this, device.mac, block);
            } catch (Exception e) {
                failure = e;
            }
            final List<RouterDevice> finalRefreshed = refreshed;
            final List<RouterDevice> finalMerged = merged;
            final Exception finalFailure = failure;
            mainHandler.post(() -> {
                deviceActionRunning = false;
                if (finalFailure == null) {
                    currentDevices.clear();
                    currentDevices.addAll(finalRefreshed);
                    savedDevices = finalMerged;
                    Toast.makeText(this, block ? "Device blocked by the router." : "Device unblocked by the router.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Could not " + (block ? "block" : "unblock") + " device: " + safeMessage(finalFailure), Toast.LENGTH_LONG).show();
                }
                updateScanButton();
                renderDevices();
            });
        });
    }

'''
anchor = '    private void logout() {'
s = replace_once(s, anchor, activity_methods + anchor, "insert device action handlers")
# Add a Block/Unblock badge to the device's top row and the action button below its details.
old = '''        if (showStatus) {
            TextView state = text(device.active ? "ACTIVE" : "INACTIVE", 10,
                    device.active ? GREEN : MUTED, true);
            state.setPadding(dp(9), dp(5), dp(9), dp(5));
            state.setBackground(pill(device.active ? Color.rgb(28, 66, 57) : Color.rgb(48, 57, 65)));
            top.addView(state, new LinearLayout.LayoutParams(-2, -2));
        }

        addDetail(outer, "IP", device.ip.isEmpty() ? "Not reported" : device.ip);
        addDetail(outer, "MAC", device.mac.isEmpty() ? "Not reported" : device.mac);
        addDetail(outer, "CONNECTION", device.interfaceType.isEmpty() ? "Not reported" : device.interfaceType);
        return outer;
'''
new = '''        if (device.blocked) {
            TextView state = text("BLOCKED", 10, RED, true);
            state.setPadding(dp(9), dp(5), dp(9), dp(5));
            state.setBackground(pill(Color.rgb(74, 38, 43)));
            top.addView(state, new LinearLayout.LayoutParams(-2, -2));
        } else if (showStatus) {
            TextView state = text(device.active ? "ACTIVE" : "INACTIVE", 10,
                    device.active ? GREEN : MUTED, true);
            state.setPadding(dp(9), dp(5), dp(9), dp(5));
            state.setBackground(pill(device.active ? Color.rgb(28, 66, 57) : Color.rgb(48, 57, 65)));
            top.addView(state, new LinearLayout.LayoutParams(-2, -2));
        }

        addDetail(outer, "IP", device.ip.isEmpty() ? "Not reported" : device.ip);
        addDetail(outer, "MAC", device.mac.isEmpty() ? "Not reported" : device.mac);
        addDetail(outer, "CONNECTION", device.interfaceType.isEmpty() ? "Not reported" : device.interfaceType);

        Button accessButton = button(device.blocked ? "Unblock" : "Block", false);
        accessButton.setTextColor(device.blocked ? GREEN : RED);
        LinearLayout.LayoutParams accessParams = matchWrap();
        accessParams.topMargin = dp(12);
        accessParams.height = dp(44);
        outer.addView(accessButton, accessParams);
        boolean validMac = device.mac != null && device.mac.matches("(?i)[0-9a-f]{2}([:-][0-9a-f]{2}){5}");
        accessButton.setEnabled(connected && !deviceActionRunning && !scanning && validMac);
        accessButton.setOnClickListener(v -> confirmDeviceAction(device));
        return outer;
'''
s = replace_once(s, old, new, "device card controls")
write(main_path, s)

# ---------------------------------------------------------------------------
# Attribution: the router control protocol follows the upstream MIT project.
# Include the complete required notice and permission text.
# ---------------------------------------------------------------------------
notice = '''# Third-party protocol attribution

The Nokia G-2425G-A login and Parental Control request flow in this application is based on the protocol documented by [surajnai567/airtel-router-api](https://github.com/surajnai567/airtel-router-api). The Android implementation is a Java port/adaptation for this application.

Upstream project license:

MIT License

Copyright (c) 2026 Suraj

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
'''
write("NOTICE.md", notice)

# Update the project documentation to describe the new controls.
readme_path = "README.md"
r = read(readme_path)
r = replace_once(r,
    "An independent, read-only Android companion for Airtel Xstream Fiber users with the Nokia G-2425G-A GPON router.",
    "An independent Android companion for Airtel Xstream Fiber users with the Nokia G-2425G-A GPON router.",
    "README project description")
r = replace_once(r,
    "- Automatic login using locally saved credentials.\n",
    "- Automatic login using locally saved credentials.\n- Block and unblock internet access for individual devices using router Parental Control.\n- Blocked status is read back from the router and shown on device cards.\n",
    "README feature list")
r = replace_once(r,
    "## Read-only access\n\nThe app only reads the router's device inventory. It cannot block devices, change Wi-Fi settings or modify router configuration.",
    "## Device access controls\n\nBlock and Unblock use the Nokia router's Parental Control interface. Blocking restricts internet access; it does not necessarily disconnect a device from Wi-Fi. The app asks for confirmation and checks the router's policy state after each change. Do not block the Android phone running the app, because it may lose access to the router.",
    "README access-control section")
r = replace_once(r,
    "6. Use All Saved Devices to see previously discovered devices and their status.\n7. Use Logout to return to the login screen.\n8. Use Forget Saved Credentials to remove locally stored credentials.",
    "6. Use Block or Unblock on a device card to change its internet access through Parental Control.\n7. Use All Saved Devices to see previously discovered devices and their status.\n8. Use Logout to return to the login screen.\n9. Use Forget Saved Credentials to remove locally stored credentials.",
    "README usage steps")
r = replace_once(r,
    "This app independently implements the read-only router device-list functionality.",
    "This app independently implements the router device-list and Parental Control functions in Java. See NOTICE.md for MIT license attribution.",
    "README reference attribution")
write(readme_path, r)

# Bump the Android version so this feature is not published as an old APK.
gradle_path = "app/build.gradle"
g = read(gradle_path)
g = replace_once(g, "        versionCode 7\n        versionName '1.1.5'", "        versionCode 8\n        versionName '1.1.8'", "Android app version")
write(gradle_path, g)

print("Block/unblock support applied.")
print("Changed: RouterClient.java, RouterDevice.java, SavedDevicesStore.java, MainActivity.java, NOTICE.md, app/build.gradle")
print("Next: run git diff --check, inspect git diff, then commit and push. Build with a NEW tag v1.1.8 only after confirming that tag does not already exist.")
