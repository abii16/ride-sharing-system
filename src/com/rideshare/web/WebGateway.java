package com.rideshare.web;

import com.rideshare.common.NetworkClient;
import com.rideshare.security.SecurityMonitor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;


public class WebGateway {
    private static int PORT = 8080;
    private static int DISPATCH_PORT = 5000;
    private static int DRIVER_PORT = 6000;

    private static final boolean DEBUG = false;
    
    
    private static final ConcurrentHashMap<String, WebSession> sessions = new ConcurrentHashMap<>();

    
    static class WebSession {
        NetworkClient client;
        String userId;
        Queue<JSONObject> messageBuffer = new LinkedBlockingQueue<>();
        Thread listenerThread;

        public WebSession(NetworkClient client) {
            this.client = client;
            
            
            this.listenerThread = new Thread(() -> {
                try {
                    while (true) {
                        JSONObject msg = client.receive();
                        if (DEBUG) System.out.println("[Gateway] Buffered msg for session: " + msg.toString());
                        messageBuffer.add(msg);
                    }
                } catch (Exception e) {
                    if (DEBUG) System.out.println("Session connection closed");
                }
            });
            this.listenerThread.start();
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 0) PORT = Integer.parseInt(args[0]);
        if (args.length > 1) DISPATCH_PORT = Integer.parseInt(args[1]);
        if (args.length > 2) DRIVER_PORT = Integer.parseInt(args[2]);

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        
        
        server.createContext("/", new StaticHandler());
        
        
        server.createContext("/api/login", new LoginHandler());
        server.createContext("/api/register", new RegisterHandler()); 
        server.createContext("/api/request", new MessageHandler()); 
        server.createContext("/api/send", new MessageHandler());    
        server.createContext("/api/updates", new UpdatesHandler());
        server.createContext("/api/ping", new PingHandler());
        server.createContext("/api/ping-dispatch", new PingDispatchHandler());
        server.createContext("/download-node", new ZipDownloadHandler());
        
        server.setExecutor(null);
        System.out.println("Web Gateway started on http://localhost:" + PORT);
        System.out.println("Connecting to Dispatch Server on port " + DISPATCH_PORT);
        System.out.println("Using Driver Service on port " + DRIVER_PORT);
        System.out.println("Open your browser to http://localhost:" + PORT);
        server.start();
    }

    

    static class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            try {
                if ("POST".equals(t.getRequestMethod())) {
                    String body = new String(readAll(t.getRequestBody()));
                    JSONObject json = new JSONObject(body);
                    
                    String username = json.getString("username");
                    String email = json.optString("email", "");
                    String password = json.getString("password");
                    String role = json.getString("role");
                    String model = json.optString("vehicle_model", "");
                    String plate = json.optString("license_plate", "");

                    // SQL Injection Shield
                    if (SecurityMonitor.hasSQLInjection(username) || SecurityMonitor.hasSQLInjection(email) || 
                        SecurityMonitor.hasSQLInjection(password) || SecurityMonitor.hasSQLInjection(role) ||
                        SecurityMonitor.hasSQLInjection(model) || SecurityMonitor.hasSQLInjection(plate)) {
                        
                        try (NetworkClient authClient = new NetworkClient("localhost", DISPATCH_PORT)) {
                            authClient.connect();
                            SecurityMonitor.logSecurityEvent(authClient, "SQL_INJECTION_BLOCKED", 
                                "WAF blocked SQL Injection in Registration payload for user: " + username);
                        } catch (Exception ignored) {}
                        
                        sendError(t, 403, "WAF Shield Alert: Malicious SQL injection payload blocked.");
                        return;
                    }

                    // XSS Scripting Shield
                    if (SecurityMonitor.hasXSS(username) || SecurityMonitor.hasXSS(email) || 
                        SecurityMonitor.hasXSS(password) || SecurityMonitor.hasXSS(model) || 
                        SecurityMonitor.hasXSS(plate)) {
                        
                        try (NetworkClient authClient = new NetworkClient("localhost", DISPATCH_PORT)) {
                            authClient.connect();
                            SecurityMonitor.logSecurityEvent(authClient, "XSS_BLOCKED", 
                                "WAF blocked Script Injection in Registration payload for user: " + username);
                        } catch (Exception ignored) {}
                        
                        sendError(t, 403, "WAF Shield Alert: Cross-Site Scripting (XSS) payload blocked.");
                        return;
                    }
                    
                    NetworkClient authClient = new NetworkClient("localhost", DISPATCH_PORT);
                    authClient.connect();
                    
                    authClient.send(new JSONObject()
                        .put("type", "AUTH_REGISTER")
                        .put("username", username)
                        .put("email", email) 
                        .put("password", password)
                        .put("role", role)
                        .put("vehicle_model", model)
                        .put("license_plate", plate)); 
                    
                    JSONObject res = authClient.receive();
                    authClient.close();
                    
                    sendJson(t, res);
                } else {
                    t.sendResponseHeaders(405, 0); 
                    t.getResponseBody().close();
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendError(t, 500, e.getMessage());
            }
        }
    }

    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String path = t.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html"; 
            
            File file = new File("web" + path);
            if (file.exists()) {
                t.sendResponseHeaders(200, file.length());
                OutputStream os = t.getResponseBody();
                Files.copy(file.toPath(), os);
                os.close();
            } else {
                String response = "404 Not Found";
                t.sendResponseHeaders(404, response.length());
                OutputStream os = t.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        }
    }

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            try {
                if ("POST".equals(t.getRequestMethod())) {
                    String body = new String(readAll(t.getRequestBody()));
                    JSONObject json = new JSONObject(body);
                    
                    String username = json.getString("username");
                    String password = json.getString("password");

                    // SQL Injection Shield
                    if (SecurityMonitor.hasSQLInjection(username) || SecurityMonitor.hasSQLInjection(password)) {
                        try (NetworkClient authClient = new NetworkClient("localhost", DISPATCH_PORT)) {
                            authClient.connect();
                            SecurityMonitor.logSecurityEvent(authClient, "SQL_INJECTION_BLOCKED", 
                                "WAF blocked SQL Injection in Login payload for user: " + username);
                        } catch (Exception ignored) {}
                        
                        sendError(t, 403, "WAF Shield Alert: Malicious SQL injection payload blocked.");
                        return;
                    }

                    // Brute Force Protection
                    if (SecurityMonitor.checkBruteForceLimit(username)) {
                        sendError(t, 429, "Access Locked: Too many failed login attempts. Try again later.");
                        return;
                    }
                    
                    NetworkClient authClient = new NetworkClient("localhost", DISPATCH_PORT);
                    try {
                        authClient.connect();
                    } catch (IOException e) {
                        sendError(t, 503, "Dispatch Server Connection Failed");
                        return;
                    }
                    
                    authClient.send(new JSONObject()
                        .put("type", "AUTH_LOGIN")
                        .put("username", username)
                        .put("password", password));
                    
                    JSONObject authRes = authClient.receive();
                    
                    JSONObject res = new JSONObject();
                    if (authRes.optString("type").equals("AUTH_RESPONSE") && authRes.getBoolean("success")) {
                        String sessionId = UUID.randomUUID().toString();
                        int userId = authRes.getInt("userId");
                        String role = authRes.getString("role");
                        
                        // Reset failed login counter
                        SecurityMonitor.resetFailedLogins(username);
                        
                        NetworkClient sessionClient;
                        
                        if ("DRIVER".equalsIgnoreCase(role)) {
                            if (DEBUG) System.out.println("[Gateway] Switching User " + userId + " to DriverService (Port " + DRIVER_PORT + ")");
                            authClient.close(); 
                            
                            sessionClient = new NetworkClient("localhost", DRIVER_PORT);
                            try {
                                sessionClient.connect();
                            } catch (IOException e) {
                                sendError(t, 503, "Driver Service Connection Failed");
                                return;
                            }
                            
                            sessionClient.send(new JSONObject()
                                .put("type", "REGISTER_DRIVER_CONN")
                                .put("driverId", userId));
                                
                        } else {
                            sessionClient = authClient;
                        }
                        
                        WebSession session = new WebSession(sessionClient); 
                        session.userId = String.valueOf(userId);
                        sessions.put(sessionId, session);
                        
                        res.put("success", true);
                        res.put("sessionId", sessionId);
                        res.put("role", role); 
                        res.put("userId", userId); 
                        
                        if (authRes.has("email")) res.put("email", authRes.getString("email"));
                        if (authRes.has("vehicle_model")) res.put("vehicle_model", authRes.getString("vehicle_model"));
                        if (authRes.has("license_plate")) res.put("license_plate", authRes.getString("license_plate"));
                        if (authRes.has("rating")) res.put("rating", authRes.getDouble("rating")); 
                    } else {
                        // Record failed login
                        try (NetworkClient countClient = new NetworkClient("localhost", DISPATCH_PORT)) {
                            countClient.connect();
                            SecurityMonitor.recordFailedLogin(username, countClient);
                        } catch (Exception ignored) {}

                        res.put("success", false);
                        String msg = authRes.optString("message", "Login failed");
                        if (msg == null || msg.isBlank()) msg = "Login failed";
                        res.put("message", msg);
                        authClient.close();
                    }

                    sendJson(t, res);
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendError(t, 500, e.getMessage());
            }
        }
    }

    private static void sendError(HttpExchange t, int code, String msg) throws IOException {
        JSONObject json = new JSONObject().put("error", msg).put("success", false);
        String resp = json.toString();
        t.getResponseHeaders().set("Content-Type", "application/json");
        t.sendResponseHeaders(code, resp.length());
        OutputStream os = t.getResponseBody();
        os.write(resp.getBytes());
        os.close();
    }

    
    static class MessageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String sid = t.getRequestHeaders().getFirst("X-Session-ID");
            WebSession session = sessions.get(sid);
            
            if (session != null && "POST".equals(t.getRequestMethod())) {
                String body = new String(readAll(t.getRequestBody()));
                try {
                    JSONObject msg = new JSONObject(body);
                    if (DEBUG) System.out.println("[Gateway] Forwarding from " + sid + ": " + msg.toString());
                    session.client.send(msg);
                    sendJson(t, new JSONObject().put("status", "OK"));
                } catch (Exception e) {
                    e.printStackTrace();
                    t.sendResponseHeaders(400, 0);
                }
            } else {
                t.sendResponseHeaders(403, 0);
            }
            t.getResponseBody().close();
        }
    }

    static class UpdatesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String sid = t.getRequestHeaders().getFirst("X-Session-ID");
            WebSession session = sessions.get(sid);
            
            if (session != null) {
                JSONArray msgs = new JSONArray();
                
                while (!session.messageBuffer.isEmpty()) {
                    msgs.put(session.messageBuffer.poll());
                }
                sendJsonArray(t, msgs);
            } else {
                t.sendResponseHeaders(403, 0);
                t.getResponseBody().close();
            }
        }
    }

    static class PingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"GET".equals(t.getRequestMethod()) && !"POST".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, 0);
                t.getResponseBody().close();
                return;
            }

            sendJson(t, new JSONObject()
                .put("type", "PONG")
                .put("success", true)
                .put("service", "WebGateway")
                .put("timestamp", String.valueOf(System.currentTimeMillis())));
        }
    }

    static class PingDispatchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if (!"GET".equals(t.getRequestMethod()) && !"POST".equals(t.getRequestMethod())) {
                t.sendResponseHeaders(405, 0);
                t.getResponseBody().close();
                return;
            }

            try (NetworkClient c = new NetworkClient("localhost", DISPATCH_PORT)) {
                c.connect();
                c.send(new JSONObject().put("type", "PING"));
                JSONObject res = c.receive();
                sendJson(t, res);
            } catch (Exception e) {
                sendError(t, 503, "Dispatch Server Connection Failed");
            }
        }
    }

    

    private static void sendJson(HttpExchange t, JSONObject json) throws IOException {
        String resp = json.toString();
        t.getResponseHeaders().set("Content-Type", "application/json");
        t.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        t.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        t.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-Session-ID");
        t.sendResponseHeaders(200, resp.length());
        OutputStream os = t.getResponseBody();
        os.write(resp.getBytes());
        os.close();
    }
    
    private static void sendJsonArray(HttpExchange t, JSONArray json) throws IOException {
        String resp = json.toString();
        t.getResponseHeaders().set("Content-Type", "application/json");
        t.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        t.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        t.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-Session-ID");
        t.sendResponseHeaders(200, resp.length());
        OutputStream os = t.getResponseBody();
        os.write(resp.getBytes());
        os.close();
    }
    
private static byte[] readAll(InputStream is) throws IOException { 
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[1024];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }
    
    static class ZipDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            System.out.println("[Gateway] Serving cluster node zip...");
            t.getResponseHeaders().set("Content-Type", "application/zip");
            t.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"rideshare_node.zip\"");
            t.sendResponseHeaders(200, 0);
            
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(t.getResponseBody())) {
                File root = new File(".");
                zipFile(root, root.getName(), zos);
            }
            t.getResponseBody().close();
        }
        
        private void zipFile(File fileToZip, String fileName, java.util.zip.ZipOutputStream zipOut) throws IOException {
            if (fileToZip.isHidden()) return;
            if (fileName.contains(".git")) return; 
            if (fileName.endsWith(".class")) return; 
            
            if (fileToZip.isDirectory()) {
                if (fileName.endsWith("/")) {
                    zipOut.putNextEntry(new java.util.zip.ZipEntry(fileName));
                    zipOut.closeEntry();
                } else {
                    zipOut.putNextEntry(new java.util.zip.ZipEntry(fileName + "/"));
                    zipOut.closeEntry();
                }
                File[] children = fileToZip.listFiles();
                if (children != null) {
                    for (File childFile : children) {
                        zipFile(childFile, fileName + "/" + childFile.getName(), zipOut);
                    }
                }
                return;
            }
            
            try (FileInputStream fis = new FileInputStream(fileToZip)) {
                java.util.zip.ZipEntry zipEntry = new java.util.zip.ZipEntry(fileName);
                zipOut.putNextEntry(zipEntry);
                byte[] bytes = new byte[1024];
                int length;
                while ((length = fis.read(bytes)) >= 0) {
                    zipOut.write(bytes, 0, length);
                }
            }
        }
    }
}
