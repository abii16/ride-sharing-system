package com.rideshare.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

public class CryptoUtil {
    private static final String SECRET_KEY_SEED = "RideShareSecureKey2026EnterpriseShield!";
    private static SecretKeySpec secretKeySpec;
    private static IvParameterSpec ivParameterSpec;

    static {
        try {
            // Deriving 256-bit AES key from seed
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha.digest(SECRET_KEY_SEED.getBytes(StandardCharsets.UTF_8));
            secretKeySpec = new SecretKeySpec(keyBytes, "AES");
            
            // Generating standard 16-byte IV for CBC mode
            byte[] ivBytes = new byte[16];
            System.arraycopy(keyBytes, 0, ivBytes, 0, 16);
            ivParameterSpec = new IvParameterSpec(ivBytes);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Hash password using SHA-256 with Salt
    public static String hashPassword(String password, String salt) {
        if (password == null) return null;
        if (salt == null || salt.isEmpty()) {
            return hashPasswordWithoutSalt(password);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String combined = password + salt;
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            return password; 
        }
    }

    private static String hashPasswordWithoutSalt(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            return password;
        }
    }

    // Generate random cryptographic salt
    public static String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] saltBytes = new byte[16];
        random.nextBytes(saltBytes);
        return bytesToHex(saltBytes);
    }

    // AES-256 Encryption
    public static String encryptAES(String plainText) {
        if (plainText == null || plainText.isEmpty()) return plainText;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec);
            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return "AES256:" + Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            return plainText; 
        }
    }

    // AES-256 Decryption
    public static String decryptAES(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) return cipherText;
        if (!cipherText.startsWith("AES256:")) return cipherText; // Non-encrypted fallback
        try {
            String cleanCipherText = cipherText.substring(7);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec);
            byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(cleanCipherText));
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return cipherText; 
        }
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
