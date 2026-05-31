package com.rideshare.security;

import com.rideshare.common.NetworkClient;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class SecurityMonitor {
    // Brute force tracking
    private static final ConcurrentHashMap<String, Integer> failedLogins = new ConcurrentHashMap<>();
    private static final int MAX_FAILED_ATTEMPTS = 5;

    // Regex patterns for detecting SQL Injection & XSS
    private static final Pattern[] SQL_INJECTION_PATTERNS = {
        Pattern.compile("(?i)UNION\\s+SELECT"),
        Pattern.compile("(?i)SELECT\\s+.*\\s+FROM"),
        Pattern.compile("(?i)INSERT\\s+INTO"),
        Pattern.compile("(?i)DROP\\s+TABLE"),
        Pattern.compile("(?i)UPDATE\\s+.*\\s+SET"),
        Pattern.compile("(?i)DELETE\\s+FROM"),
        Pattern.compile("(?i)'\\s*OR\\s*['\"\\d]"),
        Pattern.compile("(?i)\\bOR\\s+\\d+\\s*=\\s*\\d+"),
        Pattern.compile("(?i)--"),
        Pattern.compile("(?i)#"),
        Pattern.compile("(?i)/\\*")
    };

    private static final Pattern[] XSS_PATTERNS = {
        Pattern.compile("(?i)<script.*?>.*?</script.*?>"),
        Pattern.compile("(?i)javascript:"),
        Pattern.compile("(?i)\\bon[a-z]+\\s*="),
        Pattern.compile("(?i)<iframe.*?>"),
        Pattern.compile("(?i)alert\\s*\\(.*\\)")
    };

    // Flag to enable/disable security controls via Admin
    public static boolean SQL_FILTER_ENABLED = true;
    public static boolean BRUTE_FORCE_PROTECTION = true;
    public static boolean ENCRYPTION_ENABLED = true;

    // Detects SQL injection attempts in any user input
    public static boolean hasSQLInjection(String input) {
        if (input == null || !SQL_FILTER_ENABLED) return false;
        for (Pattern pattern : SQL_INJECTION_PATTERNS) {
            if (pattern.matcher(input).find()) {
                return true;
            }
        }
        return false;
    }

    // Detects XSS attempts in any user input
    public static boolean hasXSS(String input) {
        if (input == null) return false;
        for (Pattern pattern : XSS_PATTERNS) {
            if (pattern.matcher(input).find()) {
                return true;
            }
        }
        return false;
    }

    // Returns true if login should be blocked due to too many failed attempts
    public static boolean checkBruteForceLimit(String username) {
        if (!BRUTE_FORCE_PROTECTION) return false;
        Integer attempts = failedLogins.get(username);
        return attempts != null && attempts >= MAX_FAILED_ATTEMPTS;
    }

    // Registers a failed login
    public static void recordFailedLogin(String username, NetworkClient dbClient) {
        if (!BRUTE_FORCE_PROTECTION) return;
        int attempts = failedLogins.merge(username, 1, Integer::sum);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            logSecurityEvent(dbClient, "BRUTE_FORCE_ALERT", 
                "Account '" + username + "' is temporarily locked. Failed login attempts: " + attempts);
        }
    }

    // Resets failed logins on successful login
    public static void resetFailedLogins(String username) {
        failedLogins.remove(username);
    }

    // Logs security incidents directly to the MySQL audit_logs table
    public static void logSecurityEvent(NetworkClient dbClient, String eventType, String details) {
        System.err.println("[SECURITY-IDS] Alert! Type: " + eventType + " | Details: " + details);
        if (dbClient == null) return;
        
        try {
            // Escape details to prevent nested injection
            String safeDetails = escapeSql(details);
            String sql = String.format("INSERT INTO audit_logs (event_type, details) VALUES ('%s', '%s')", 
                eventType, safeDetails);
            
            dbClient.send(new JSONObject().put("type", "DB_UPDATE").put("sql", sql));
            // Read response asynchronously or block shortly
            dbClient.receive();
        } catch (Exception e) {
            System.err.println("[SECURITY-IDS] Failed to write security event to database: " + e.getMessage());
        }
    }

    // Simple sanitization helper
    public static String escapeSql(String str) {
        if (str == null) return "";
        return str.replace("'", "''").replace("\\", "\\\\");
    }

    // Input sanitizer for safe query generation
    public static String sanitize(String input) {
        if (input == null) return null;
        // Strip out single quotes, dashes and hashes to mitigate injection safely
        return input.replace("'", "").replace("--", "").replace("#", "");
    }
}
