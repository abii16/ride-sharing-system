package com.rideshare.database;

import com.rideshare.common.NetworkClient;
import com.rideshare.common.cluster.ClusterListener;
import com.rideshare.common.cluster.ClusterManager;
import com.rideshare.common.cluster.ClusterManager.PeerInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DatabaseService implements ClusterListener {
    // Default config
    private static int PORT = 7000;
    private static int CLUSTER_PORT = 8000;
    private static int NODE_ID = 1;
    
    // Configurable DB properties
    private static String DB_URL = System.getProperty("db.url", "jdbc:mysql://localhost:3306/rideshare_db");
    private static String DB_USER = System.getProperty("db.user", "root");
    private static String DB_PASS = System.getProperty("db.pass", ""); 

    private static boolean DEBUG = false; // Toggle logging

    private static Connection conn;
    private static ClusterManager cluster;
    
    // Map ID -> DbPort
    private static final Map<Integer, Integer> peerDbPorts = new HashMap<>();
    private static final Map<Integer, String> peerHosts = new HashMap<>();

    public static void main(String[] args) {
        if (args.length > 0) {
            NODE_ID = Integer.parseInt(args[0]);
            PORT = Integer.parseInt(args[1]);
            CLUSTER_PORT = Integer.parseInt(args[2]);
        }
        
        System.out.println("Starting Database Service Node " + NODE_ID + " on port " + PORT + " (Cluster: " + CLUSTER_PORT + ")...");
        System.out.println("Database Configuration:");
        System.out.println("  URL:  " + DB_URL);
        System.out.println("  User: " + DB_USER);
        
        List<PeerInfo> peers = new ArrayList<>();
        // Parse peers from args [3+] format: id:host:cPort:dPort
        for (int i=3; i<args.length; i++) {
            String[] parts = args[i].split(":");
            int pid = Integer.parseInt(parts[0]);
            String phost = parts[1];
            int pcport = Integer.parseInt(parts[2]);
            int pdport = Integer.parseInt(parts[3]);
            peers.add(new PeerInfo(pid, phost, pcport));
            peerDbPorts.put(pid, pdport);
            peerHosts.put(pid, phost);
        }

        try {
            conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
            System.out.println("Connected to MySQL successfully.");

            // Start Cluster
            DatabaseService service = new DatabaseService();
            cluster = new ClusterManager(NODE_ID, CLUSTER_PORT, peers, service);
            cluster.start();

            // Start Client Server
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(new DatabaseHandler(clientSocket)).start();
                }
            }
        } catch (SQLException e) {
            System.err.println("!!! DATABASE CONNECTION FAILED !!!");
            System.err.println("Error: " + e.getMessage());
            System.err.println("Check if MySQL is running at " + DB_URL);
            System.err.println("Check username/password.");
            // Keep process alive to show error
            try { Thread.sleep(30000); } catch (InterruptedException ie) {}
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // --- ClusterListener Implementation ---

    @Override
    public void applyWrite(String sql) {
        // SAFETY CHECK: If we are connected to a remote DB (Shared DB mode), 
        // the Leader has already executed this SQL on our behalf.
        // We only apply writes if we are running a LOCAL database replica.
        boolean isLocalDB = DB_URL.contains("localhost") || DB_URL.contains("127.0.0.1");
        
        if (!isLocalDB) {
            if (DEBUG) System.out.println("[DB-Rep] Skipping replication (Shared DB mode detected).");
            return;
        }

        // Apply replicated SQL without broadcasting
        if (DEBUG) System.out.println("[DB-Rep] Applying replicated SQL: " + sql);
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
             System.err.println("[DB-Rep] Error applying replication: " + e.getMessage());
        }
    }

    @Override
    public JSONObject createSnapshot() {
        JSONObject snapshot = new JSONObject();
        // Dump all tables
        String[] tables = {"users", "drivers", "rides"}; 
        for (String table : tables) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM " + table)) {
                 snapshot.put(table, resultSetToJson(rs));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return snapshot;
    }

    @Override
    public void applySnapshot(JSONObject snapshot) {
        // Truncate tables and load new data
        String[] tables = {"rides", "drivers", "users"}; // Reverse order for FK
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String table : tables) {
                if (snapshot.has(table)) {
                    stmt.executeUpdate("TRUNCATE TABLE " + table);
                    
                    JSONArray rows = snapshot.getJSONArray(table);
                    for (int i=0; i<rows.length(); i++) {
                        JSONObject row = rows.getJSONObject(i);
                        // Construct INSERT
                        StringBuilder keys = new StringBuilder();
                        StringBuilder vals = new StringBuilder();
                        for (String key : row.keySet()) {
                            if (keys.length() > 0) { keys.append(","); vals.append(","); }
                            keys.append(key);
                            vals.append("'").append(escape(row.get(key).toString())).append("'");
                        }
                        String sql = "INSERT INTO " + table + " (" + keys + ") VALUES (" + vals + ")";
                        stmt.executeUpdate(sql);
                    }
                }
            }
             stmt.execute("SET FOREIGN_KEY_CHECKS=1");
             System.out.println("[DB-Sync] Snapshot applied.");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    
    private String escape(String s) {
        return s.replace("'", "\\'");
    }
    
    @Override
    public void forwardToLeader(JSONObject request) {
       // Handled in DatabaseHandler
    }


    // --- Helper ---
    
    private static JSONArray resultSetToJson(ResultSet rs) throws SQLException {
        JSONArray jsonArray = new JSONArray();
        while (rs.next()) {
            JSONObject obj = new JSONObject();
            int total_rows = rs.getMetaData().getColumnCount();
            for (int i = 0; i < total_rows; i++) {
                String columnName = rs.getMetaData().getColumnLabel(i + 1);
                obj.put(columnName, rs.getObject(i + 1));
            }
            jsonArray.put(obj);
        }
        return jsonArray;
    }


    // --- Client Handler ---

    private static class DatabaseHandler implements Runnable {
        private final Socket socket;

        public DatabaseHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    JSONObject request = new JSONObject(inputLine);
                    JSONObject response;
                    
                    String type = request.getString("type");
                    
                    if ("DB_UPDATE".equals(type) && !cluster.isLeader()) {
                         int lid = cluster.getLeaderId();
                         if (lid != -1 && peerDbPorts.containsKey(lid)) {
                             if (DEBUG) System.out.println("[DB] Proxing Write to Leader " + lid);
                             response = proxyToLeader(lid, request);
                         } else {
                             response = new JSONObject().put("status", "ERROR").put("message", "No Leader Available");
                         }
                    } else {
                        response = processRequestLocal(request);
                        
                        // If it was a successful write on Leader, broadcast it
                        if ("DB_UPDATE".equals(type) && "OK".equals(response.optString("status")) && cluster.isLeader()) {
                             // Stronger safety: wait for majority ACKs so a leader crash doesn't silently lose writes.
                             boolean replicated = cluster.broadcastWriteWithQuorumAck(request.getString("sql"), 2500);
                             response.put("replicated", replicated);
                             if (!replicated) {
                                 response.put("warning", "Write executed on leader but replication quorum was not reached.");
                             }
                        }
                    }
                    
                    out.println(response.toString());
                }

            } catch (IOException e) {
                // System.out.println("Client disconnected.");
            }
        }
        
        private JSONObject proxyToLeader(int leaderId, JSONObject req) {
             String host = peerHosts.get(leaderId);
             if (host == null || host.isBlank()) {
                 try {
                     host = cluster.getPeerHost(leaderId);
                 } catch (Exception ignored) {
                 }
             }

             Integer portObj = peerDbPorts.get(leaderId);
             int port = (portObj != null) ? portObj : (7000 + leaderId - 1);

             if (host == null || host.isBlank()) {
                 return new JSONObject().put("status", "ERROR").put("message", "No route to leader (unknown host)");
             }
             try (NetworkClient nc = new NetworkClient(host, port)) {
                 nc.connect();
                 nc.send(req);
                 return nc.receive();
             } catch (Exception e) {
                 return new JSONObject().put("status", "ERROR").put("message", "Proxy Failed: " + e.getMessage());
             }
        }

        private JSONObject processRequestLocal(JSONObject request) {
            String type = request.getString("type");
            JSONObject response = new JSONObject();

            try {
                if ("DB_QUERY".equals(type)) {
                    String sql = request.getString("sql");
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql);
                    response.put("status", "OK");
                    response.put("data", resultSetToJson(rs));
                    rs.close();
                    stmt.close();

                } else if ("DB_UPDATE".equals(type)) {
                    String sql = request.getString("sql");
                    PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                    int rows = stmt.executeUpdate();
                    
                    response.put("status", "OK");
                    response.put("rowsAffected", rows);
                    
                    ResultSet generatedKeys = stmt.getGeneratedKeys();
                    if (generatedKeys.next()) {
                        response.put("generatedId", generatedKeys.getInt(1));
                    }
                    stmt.close();
                } else {
                    response.put("status", "ERROR");
                    response.put("message", "Unknown Request Type");
                }
            } catch (SQLException e) {
                response.put("status", "ERROR");
                response.put("message", e.getMessage());
            }
            return response;
        }
    }
}
