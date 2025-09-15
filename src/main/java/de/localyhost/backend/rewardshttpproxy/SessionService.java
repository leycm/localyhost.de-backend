package de.localyhost.backend.rewardshttpproxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Map<String, String>> sessionCookies = new ConcurrentHashMap<>();
    private final String STORAGE_PATH = "proxy_data/";

    public SessionService() {
        new File(STORAGE_PATH).mkdirs();

        loadExistingSessions();
    }

    public String[] getOrCreateSession(ServerHttpRequest request, ServerHttpResponse response) {
        String uuid = getCookieValue(request, "proxy_uuid");
        String userKey = getCookieValue(request, "proxy_key");

        if (uuid == null || userKey == null) {
            uuid = UUID.randomUUID().toString();
            userKey = generateRandomKey();

            addCookie(response, "proxy_uuid", uuid);
            addCookie(response, "proxy_key", userKey);

            sessionCookies.put(uuid, new ConcurrentHashMap<>());
        } else {
            if (!sessionCookies.containsKey(uuid)) {
                loadCookiesFromFile(uuid);
            }
        }

        return new String[]{uuid, userKey};
    }

    public String getCookieValue(@NotNull ServerHttpRequest request, @NotNull String name) {
        for (List<HttpCookie> list : request.getCookies().values()) {
            for (HttpCookie cookie : list) {
                if (name.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    public String getCookieHeader(String uuid) {
        Map<String, String> cookies = sessionCookies.get(uuid);
        if (cookies == null || cookies.isEmpty()) {
            return null;
        }

        StringBuilder cookieHeader = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (!cookieHeader.isEmpty()) {
                cookieHeader.append("; ");
            }
            cookieHeader.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return cookieHeader.toString();
    }

    public void handleResponseCookies(@NotNull ResponseEntity<String> responseEntity, String uuid, ServerHttpResponse response) {
        List<String> setCookieHeaders = responseEntity.getHeaders().get("Set-Cookie");
        if (setCookieHeaders == null || setCookieHeaders.isEmpty()) {
            return;
        }

        Map<String, String> cookies = sessionCookies.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());

        for (String cookieHeader : setCookieHeaders) {
            parseCookie(cookieHeader, cookies);
        }

        saveCookiesToFile(uuid, cookies);
    }

    public ResponseEntity<List<Map<String, String>>> getCredentials(String uuid, ServerHttpRequest request) {
        try {
            String userKey = getCookieValue(request, "proxy_key");
            String requestUuid = getCookieValue(request, "proxy_uuid");

            if (userKey == null || !uuid.equals(requestUuid)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            File credFile = new File(STORAGE_PATH + uuid + "_credentials.json");
            if (!credFile.exists()) {
                return ResponseEntity.ok(new ArrayList<>());
            }

            List<Map<String, String>> credentials = objectMapper.readValue(credFile, List.class);

            for (Map<String, String> cred : credentials) {
                cred.remove("password");
            }

            return ResponseEntity.ok(credentials);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    public ResponseEntity<Void> deleteCredential(String uuid, int index, ServerHttpRequest request) {
        try {
            String userKey = getCookieValue(request, "proxy_key");
            String requestUuid = getCookieValue(request, "proxy_uuid");

            if (userKey == null || !uuid.equals(requestUuid)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            File credFile = new File(STORAGE_PATH + uuid + "_credentials.json");
            if (!credFile.exists()) {
                return ResponseEntity.notFound().build();
            }

            List<Map<String, String>> credentials = objectMapper.readValue(credFile, List.class);

            if (index >= 0 && index < credentials.size()) {
                credentials.remove(index);
                objectMapper.writeValue(credFile, credentials);
                return ResponseEntity.ok().build();
            } else {
                return ResponseEntity.badRequest().build();
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private void addCookie(@NotNull ServerHttpResponse response, String name, String value) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .path("/")
                .maxAge(86400 * 30)
                .httpOnly(true)
                .secure(false)
                .build();
        response.addCookie(cookie);
    }

    private String generateRandomKey() {
        SecureRandom random = new SecureRandom();
        byte[] key = new byte[32];
        random.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private void parseCookie(String cookieHeader, Map<String, String> cookies) {
        try {
            String[] parts = cookieHeader.split(";");
            String[] mainPart = parts[0].split("=", 2);

            if (mainPart.length == 2) {
                String name = mainPart[0].trim();
                String value = mainPart[1].trim();

                if (!name.startsWith("proxy_")) {
                    cookies.put(name, value);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void saveCookiesToFile(String uuid, Map<String, String> cookies) {
        try {
            File cookieFile = new File(STORAGE_PATH + uuid + "_cookies.json");
            objectMapper.writeValue(cookieFile, cookies);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadCookiesFromFile(String uuid) {
        try {
            File cookieFile = new File(STORAGE_PATH + uuid + "_cookies.json");
            if (cookieFile.exists()) {
                Map<String, String> cookies = objectMapper.readValue(cookieFile, Map.class);
                sessionCookies.put(uuid, new ConcurrentHashMap<>(cookies));
            } else {
                sessionCookies.put(uuid, new ConcurrentHashMap<>());
            }
        } catch (Exception e) {
            e.printStackTrace();
            sessionCookies.put(uuid, new ConcurrentHashMap<>());
        }
    }

    private void loadExistingSessions() {
        File storageDir = new File(STORAGE_PATH);
        if (!storageDir.exists()) {
            return;
        }

        File[] cookieFiles = storageDir.listFiles((dir, name) -> name.endsWith("_cookies.json"));
        if (cookieFiles != null) {
            for (File cookieFile : cookieFiles) {
                String fileName = cookieFile.getName();
                String uuid = fileName.replace("_cookies.json", "");
                loadCookiesFromFile(uuid);
            }
        }
    }
}
