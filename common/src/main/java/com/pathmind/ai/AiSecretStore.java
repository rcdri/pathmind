package com.pathmind.ai;

import com.pathmind.data.SettingsManager;
import com.pathmind.data.SettingsManager.Settings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stores provider tokens as AES-GCM ciphertext in Pathmind settings.
 *
 * <p>The encryption key is installation-bound, not a replacement for a native
 * operating-system keychain. Keeping this boundary separate lets a future
 * keychain implementation migrate without changing provider or UI code.</p>
 */
public final class AiSecretStore {
    private static final String VERSION = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AiSecretStore() {
    }

    public static boolean hasSecret(AiProviderType provider) {
        if (provider == null) return false;
        String value = SettingsManager.getCurrent().aiProviderSecrets.get(provider.id());
        return value != null && !value.isBlank();
    }

    public static String read(AiProviderType provider) {
        if (provider == null) return "";
        String payload = SettingsManager.getCurrent().aiProviderSecrets.get(provider.id());
        if (payload == null || payload.isBlank()) return "";
        try {
            String[] pieces = payload.split(":", 3);
            if (pieces.length != 3 || !VERSION.equals(pieces[0])) return "";
            byte[] iv = Base64.getDecoder().decode(pieces[1]);
            byte[] encrypted = Base64.getDecoder().decode(pieces[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    public static void save(AiProviderType provider, String token) {
        if (provider == null) return;
        Settings settings = SettingsManager.getCurrent();
        String normalized = token == null ? "" : token.trim();
        if (normalized.isEmpty()) {
            settings.aiProviderSecrets.remove(provider.id());
        } else {
            try {
                byte[] iv = new byte[IV_BYTES];
                RANDOM.nextBytes(iv);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
                byte[] encrypted = cipher.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
                settings.aiProviderSecrets.put(provider.id(), VERSION + ":"
                    + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(encrypted));
            } catch (Exception exception) {
                throw new IllegalStateException("Could not encrypt the AI provider key", exception);
            }
        }
        SettingsManager.save(settings);
    }

    private static SecretKeySpec key() throws Exception {
        Path gameDirectory = SettingsManager.getCurrent() == null ? Path.of("") :
            net.minecraft.client.Minecraft.getInstance() != null && net.minecraft.client.Minecraft.getInstance().gameDirectory != null
                ? net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath() : Path.of(System.getProperty("user.home", ""), ".minecraft");
        String material = "pathmind-ai-key-v1|" + System.getProperty("user.name", "") + "|"
            + System.getProperty("os.name", "") + "|" + gameDirectory.toAbsolutePath();
        return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)), "AES");
    }
}
