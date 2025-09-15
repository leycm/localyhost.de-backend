package de.localyhost.backend;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.util.Base64;

public class AESKeyGen {
    public static void main(String[] args) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();

        String base64Key = Base64.getEncoder().encodeToString(key.getEncoded());
        System.out.println("Dein PROXY_AES_KEY_B64:");
        System.out.println(base64Key);
    }
}
