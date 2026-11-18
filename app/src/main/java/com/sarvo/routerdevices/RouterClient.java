package com.sarvo.routerdevices;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookieHandler;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Client for the Nokia G-2425G-A web interface.
 * Login and parental-control protocol are based on the public MIT-licensed
 * airtel-router-api project. Device actions are limited to router parental control.
 */
final class RouterClient {
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final Pattern PEM_PATTERN = Pattern.compile(
            "-----BEGIN PUBLIC KEY-----.*?-----END PUBLIC KEY-----", Pattern.DOTALL);
    private static final Pattern NONCE_PATTERN = Pattern.compile("var\\s+nonce\\s*=\\s*\"(.*?)\";", Pattern.DOTALL);
    private static final Pattern TOKEN_PATTERN = Pattern.compile("var\\s+token\\s*=\\s*\"(.*?)\";", Pattern.DOTALL);
    private static final Pattern CSRF_INPUT_PATTERN = Pattern.compile("csrf_token[\\\"\\s]*value=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEVICE_ARRAY_PATTERN = Pattern.compile(
            "var\\s+device_?cfg\\s*=\\s*\\[(.*?)\\];", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern DEVICE_PATTERN = Pattern.compile(
            "\\{_oid:(\\d+),\\s*HostName:'([^']*)',\\s*"
                    + "IPAddress:'([^']*)',\\s*MACAddress:'([^']*)',\\s*"
                    + "Active:(\\d),\\s*InterfaceType:'([^']*)'\\s*\\}",
            Pattern.DOTALL);

    private final String baseUrl;
    private final String username;
    private final String password;
    private final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private String publicKeyPem;
    private String csrfToken;
    private boolean authenticated;

    RouterClient(String routerHost, String username, String password) {
        String host = routerHost == null ? "192.168.1.1" : routerHost.trim();
        if (host.isEmpty() || host.contains("/") || host.contains(" ") || host.contains("@")) {
            throw new IllegalArgumentException("Enter the router IP address, for example 192.168.1.1.");
        }
        this.baseUrl = "http://" + host;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        CookieHandler.setDefault(cookieManager);
    }

    /** Reuses the authenticated router session for later scans. */
    List<RouterDevice> connectAndReadDevices() throws Exception {
        if (!authenticated) {
            cookieManager.getCookieStore().removeAll();
            String loginPage = get("/");
            LoginParams params = parseLoginParams(loginPage);
            login(params);
        }

        String html = get("/parental_control.cgi");
        if (looksLikeLoginPage(html)) {
            authenticated = false;
            cookieManager.getCookieStore().removeAll();
            LoginParams retryParams = parseLoginParams(html);
            login(retryParams);
            html = get("/parental_control.cgi");
        }
        List<RouterDevice> devices = parseDevices(html);
        if (devices.isEmpty()) {
            throw new IOException("The router login succeeded, but its device list was not recognised. "
                    + "The Airtel firmware may use a different page format.");
        }
        return devices;
    }

    /** Ends the router web session and clears local cookies. */
    void logout() {
        try {
            if (authenticated) {
                HttpURLConnection connection = open("/login.cgi?out", "GET");
                int status = connection.getResponseCode();
                readResponse(connection, status);
                connection.disconnect();
            }
        } catch (Exception ignored) {
            // Local logout must complete even if the router cannot be reached.
        } finally {
            authenticated = false;
            cookieManager.getCookieStore().removeAll();
        }
    }

    private void login(LoginParams params) throws Exception {
        this.publicKeyPem = params.publicKey;
        byte[] loginAesKey = randomBytes(16);
        byte[] loginAesIv = randomBytes(16);

        String keyB64 = Base64.encodeToString(loginAesKey, Base64.NO_WRAP);
        String ivB64 = Base64.encodeToString(loginAesIv, Base64.NO_WRAP);
        String payload = "&username=" + username
                + "&password=" + quoteLikePython(password)
                + "&csrf_token=" + params.token
                + "&nonce=" + params.nonce
                + "&enckey=" + base64UrlEscape(keyB64)
                + "&enciv=" + base64UrlEscape(ivB64);

        String encrypted = encryptPostData(publicKeyPem, payload);
        HttpURLConnection connection = open("/login.cgi", "POST");
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        writeBody(connection, encrypted);
        int status = connection.getResponseCode();
        readResponse(connection, status); // Drain body and keep the session cookies.
        connection.disconnect();
        if (status != 299) {
            throw new IOException("Router login was rejected (HTTP " + status + "). Check the admin username/password.");
        }
        authenticated = true;
    }

    private String get(String path) throws Exception {
        HttpURLConnection connection = open(path, "GET");
        int status = connection.getResponseCode();
        String body = readResponse(connection, status);
        connection.disconnect();
        if (status < 200 || status >= 400) {
            throw new IOException("Router returned HTTP " + status + " while reading " + path + ".");
        }
        if (body == null || body.trim().isEmpty()) {
            throw new IOException("The router returned an empty response for " + path + ".");
        }
        updateCsrf(body);
        return body;
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        c.setRequestProperty("Referer", baseUrl);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        return c;
    }

    private static void writeBody(HttpURLConnection c, String body) throws IOException {
        c.setDoOutput(true);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = c.getOutputStream()) {
            output.write(bytes);
            output.flush();
        }
    }

    private static String readResponse(HttpURLConnection c, int status) throws IOException {
        InputStream input = status >= 400 ? c.getErrorStream() : c.getInputStream();
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void updateCsrf(String html) {
        Matcher matcher = CSRF_INPUT_PATTERN.matcher(html);
        if (matcher.find()) {
            csrfToken = matcher.group(1);
            return;
        }
        matcher = TOKEN_PATTERN.matcher(html);
        if (matcher.find()) csrfToken = matcher.group(1);
    }

    private static LoginParams parseLoginParams(String html) throws Exception {
        Matcher pem = PEM_PATTERN.matcher(html);
        Matcher nonce = NONCE_PATTERN.matcher(html);
        Matcher token = TOKEN_PATTERN.matcher(html);
        if (!pem.find() || !nonce.find() || !token.find()) {
            throw new IOException("Could not find the router's login key, nonce and CSRF token. "
                    + "Confirm that the router web page is reachable and uses the expected firmware.");
        }
        String publicKey = pem.group().replace("\\", "");
        return new LoginParams(publicKey, nonce.group(1), token.group(1));
    }

    private static String encryptPostData(String publicKeyPem, String plaintext) throws Exception {
        byte[] aesKey = randomBytes(16);
        byte[] aesIv = randomBytes(16);
        Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(aesIv));
        byte[] ciphertext = aes.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        String ct = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
                .replace('+', '-').replace('/', '_').replace("=", "");

        String aesInfo = Base64.encodeToString(aesKey, Base64.NO_WRAP)
                + " " + Base64.encodeToString(aesIv, Base64.NO_WRAP);
        PublicKey publicKey = parsePublicKey(publicKeyPem);
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, publicKey);
        String ck = Base64.encodeToString(rsa.doFinal(aesInfo.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        ck = base64UrlEscape(ck);
        return "encrypted=1&ct=" + ct + "&ck=" + ck;
    }

    private static PublicKey parsePublicKey(String pem) throws Exception {
        String body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.decode(body, Base64.DEFAULT);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String base64UrlEscape(String value) {
        return value.replace('+', '-').replace('/', '_').replace('=', '.');
    }

    // urllib.parse.quote(password) uses %20 for spaces and leaves '/' unescaped.
    private static String quoteLikePython(String value) throws Exception {
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                .replace("+", "%20").replace("%7E", "~").replace("*", "%2A");
        return encoded.replace("%2F", "/");
    }

    private static boolean looksLikeLoginPage(String html) {
        return html.length() <= 150 || html.contains("submit_login") || html.contains("var nonce");
    }

    private static List<RouterDevice> parseDevices(String html) throws IOException {
        if (html == null) throw new IOException("The router did not return a device page.");
        Matcher array = DEVICE_ARRAY_PATTERN.matcher(html);
        if (!array.find()) {
            throw new IOException("Could not find deviceCfg/device_cfg in the router page. This firmware may differ.");
        }
        Set<String> blockedMacs = parseBlockedMacs(html);
        Matcher device = DEVICE_PATTERN.matcher(array.group(1));
        List<RouterDevice> result = new ArrayList<>();
        while (device.find()) {
            int oid;
            try {
                oid = Integer.parseInt(device.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            result.add(new RouterDevice(oid,
                    decodeJsString(device.group(2)),
                    decodeJsString(device.group(3)),
                    decodeJsString(device.group(4)),
                    "1".equals(device.group(5)),
                    decodeJsString(device.group(6)),
                    blockedMacs.contains(normalizeMacAddress(device.group(4)))));
        }
        Collections.sort(result, new Comparator<RouterDevice>() {
            @Override public int compare(RouterDevice a, RouterDevice b) {
                if (a.active != b.active) return a.active ? -1 : 1;
                return a.ip.compareTo(b.ip);
            }
        });
        return result;
    }


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

    private static String decodeJsString(String value) {
        if (value == null) return "";
        return value.replace("\\'", "'").replace("\\\"", "\"")
                .replace("\\n", " ").replace("\\r", " ").trim();
    }

    private static final class LoginParams {
        final String publicKey;
        final String nonce;
        final String token;
        LoginParams(String publicKey, String nonce, String token) {
            this.publicKey = publicKey;
            this.nonce = nonce;
            this.token = token;
        }
    }
}
