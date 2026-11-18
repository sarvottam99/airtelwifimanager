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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Read-only client for the Nokia G-2425G-A web interface.
 * Protocol is based on the public airtel-router-api implementation.
 * It only logs in and reads /parental_control.cgi; it never changes router settings.
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

    List<RouterDevice> connectAndReadDevices() throws Exception {
        authenticated = false;
        cookieManager.getCookieStore().removeAll();
        String loginPage = get("/");
        LoginParams params = parseLoginParams(loginPage);
        login(params);

        String html = get("/parental_control.cgi");
        if (looksLikeLoginPage(html)) {
            authenticated = false;
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
                    decodeJsString(device.group(6))));
        }
        Collections.sort(result, new Comparator<RouterDevice>() {
            @Override public int compare(RouterDevice a, RouterDevice b) {
                if (a.active != b.active) return a.active ? -1 : 1;
                return a.ip.compareTo(b.ip);
            }
        });
        return result;
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
