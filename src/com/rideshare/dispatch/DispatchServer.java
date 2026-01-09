package com.rideshare.dispatch;

import com.rideshare.common.MessageType;
import com.rideshare.common.NetworkClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DispatchServer {
    private static int PORT = 5000;
    private static int DB_PORT = 7000;
    private static int DRIVER_PORT = 6000;
    
    
    
    public static final ConcurrentHashMap<Integer, PrintWriter> onlinePassengers = new ConcurrentHashMap<>();
    

    
    
    

    public static void main(String[] args) {
        if (args.length > 0) PORT = Integer.parseInt(args[0]);
        if (args.length > 1) DB_PORT = Integer.parseInt(args[1]);
        if (args.length > 2) DRIVER_PORT = Integer.parseInt(args[2]);

        System.out.println("Starting Dispatch Server on port " + PORT + "...");
        System.out.println("Connecting to Database Service on port " + DB_PORT);
        System.out.println("Connecting to Driver Service on port " + DRIVER_PORT);
        
        ExecutorService pool = Executors.newFixedThreadPool(100);

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket socket = serverSocket.accept();
                pool.execute(new ClientHandler(socket));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    
    static class ClientHandler implements Runnable {
        private Socket socket;
        private int userId = -1; 
        private static final boolean DEBUG = false;
        
        
        private NetworkClient dbClient;
        private NetworkClient driverServiceClient;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                
                this.dbClient = new NetworkClient("localhost", DB_PORT);
                connectWithRetry(this.dbClient, "DatabaseService");
                
                this.driverServiceClient = new NetworkClient("localhost", DRIVER_PORT);
                connectWithRetry(this.driverServiceClient, "DriverService");
                
                
                java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

                String line;
                while ((line = in.readLine()) != null) {
                    if (DEBUG) System.out.println("[Dispatch] Received: " + line);
                    JSONObject request = new JSONObject(line);
                    handleRequest(request, out);
                }
                
                
                if (userId != -1) {
                    onlinePassengers.remove(userId);
                    if (DEBUG) System.out.println("User " + userId + " disconnected.");
                }

            } catch (Exception e) {
                 if (DEBUG) System.out.println("Client disconnected or error: " + e.getMessage());
                 if (DEBUG) e.printStackTrace();
            } finally {
                
                try { if(dbClient!=null) dbClient.close(); } catch(Exception e){}
                try { if(driverServiceClient!=null) driverServiceClient.close(); } catch(Exception e){}
            }
        }

        
        private void connectWithRetry(NetworkClient client, String serviceName) throws IOException {
            int retries = 20; 
            while (retries > 0) {
                try {
                    client.connect();
                    return; 
                } catch (IOException e) {
                    retries--;
                    System.out.println("[Dispatch] Waiting for " + serviceName + "... (" + retries + " attempts left)");
                    try { Thread.sleep(2000); } catch (InterruptedException ie) {} 
                }
            }
            throw new IOException("Could not connect to " + serviceName + " after 20 attempts.");
        }

        private void handleRequest(JSONObject doc, PrintWriter out) throws IOException {
             String type = doc.optString("type", ""); 

            if (type.equals(MessageType.PING.name()) || type.equals("PING")) {
                out.println(new JSONObject()
                    .put("type", MessageType.PONG.name())
                    .put("success", true)
                    .put("service", "DispatchServer")
                    .put("timestamp", String.valueOf(System.currentTimeMillis()))
                    .toString());
                return;
            }

            if (type.equals("AUTH_REGISTER")) {
                String username = doc.getString("username");
                String email = doc.optString("email", "");
                String password = doc.getString("password");
                String role = doc.getString("role"); 

                System.out.println("[Dispatch] Registering: " + username + " (" + email + ") as " + role);

                
                
                String sql = String.format("INSERT IGNORE INTO users (username, email, password_hash, role) VALUES ('%s', '%s', '%s', '%s')", 
                    username, email, password, role);
                
                dbClient.send(new JSONObject().put("type", "DB_UPDATE").put("sql", sql));
                JSONObject dbRes = dbClient.receive();

                if ("ERROR".equalsIgnoreCase(dbRes.optString("status"))) {
                    out.println(new JSONObject()
                        .put("type", "AUTH_RESPONSE")
                        .put("success", false)
                        .put("message", "Registration failed: " + dbRes.optString("message", "DB error"))
                        .toString());
                    return;
                }
                
                
                
                
                if (dbRes.has("generatedId") && dbRes.getInt("generatedId") > 0) {
                     int newUserId = dbRes.getInt("generatedId");
                     
                     
                     if ("DRIVER".equals(role)) {
                         
                         String model = doc.optString("vehicle_model", "Pending Model");
                         String plate = doc.optString("license_plate", "NEW-" + newUserId);
                         
                         
                         String dSql = String.format("INSERT INTO drivers (user_id, vehicle_model, license_plate, status) VALUES (%d, '%s', '%s', 'PENDING')", 
                            newUserId, model, plate);
                            
                         dbClient.send(new JSONObject().put("type", "DB_UPDATE").put("sql", dSql));

                         JSONObject dRes = dbClient.receive();
                         if ("ERROR".equalsIgnoreCase(dRes.optString("status")) || dRes.optInt("rowsAffected", 0) <= 0) {
                             out.println(new JSONObject()
                                 .put("type", "AUTH_RESPONSE")
                                 .put("success", false)
                                 .put("message", "Driver registration failed (plate may already exist)")
                                 .toString());
                             return;
                         }
                         
                         
                         out.println(new JSONObject().put("type", "AUTH_RESPONSE").put("success", true).put("message", "Registration Successful! Account pending admin approval.").toString());

                     } else {
                        
                        out.println(new JSONObject().put("type", "AUTH_RESPONSE").put("success", true).put("message", "Registration Successful!").toString());
                     }

                } else {
                     out.println(new JSONObject().put("type", "AUTH_RESPONSE").put("success", false).put("message", "Username already exists").toString());
                }

            } else if (type.equals(MessageType.AUTH_LOGIN.name())) {
                String username = doc.getString("username");
                System.out.println("[Dispatch] Processing Login for: " + username);
                
                
                JSONObject dbReq = new JSONObject()
                    .put("type", "DB_QUERY")
                    .put("sql", "SELECT u.*, d.vehicle_model, d.license_plate, d.status as driver_status FROM users u LEFT JOIN drivers d ON u.id = d.user_id WHERE u.username='" + username + "'");
                
                dbClient.send(dbReq);
                JSONObject dbRes = dbClient.receive();
                
                boolean success = false;
                JSONArray users = dbRes.optJSONArray("data");
                if (users != null && users.length() > 0) {
                    JSONObject user = users.getJSONObject(0);
                    
                    if (user.getString("password_hash").equals(doc.getString("password"))) {
                        String role = user.getString("role");
                        
                        
                        if ("DRIVER".equals(role)) {
                            
                            String status = user.optString("driver_status");
                            if ("PENDING".equalsIgnoreCase(status) || "REJECTED".equalsIgnoreCase(status)) {
                                out.println(new JSONObject()
                                    .put("type", "AUTH_RESPONSE")
                                    .put("success", false)
                                    .put("message", "Account Status: " + status + ". Please wait for admin approval.")
                                    .toString());
                                return; 
                            }
                        }
                        
                        this.userId = user.getInt("id");
                        if ("PASSENGER".equals(role)) {
                            onlinePassengers.put(userId, out); 
                        }
                        
                        out.println(new JSONObject()
                            .put("type", "AUTH_RESPONSE")
                            .put("success", true)
                            .put("userId", userId)
                            .put("email", user.optString("email", ""))
                            .put("vehicle_model", user.optString("vehicle_model", "N/A"))
                            .put("license_plate", user.optString("license_plate", "N/A"))
                            .put("rating", user.optDouble("rating", 5.0))
                            .put("role", role).toString());
                        success = true;
                    }
                }
                
                if (!success) {
                    System.out.println("Login Failed for " + username);
                    out.println(new JSONObject().put("type", "AUTH_RESPONSE").put("success", false).put("message", "Invalid credentials").toString());
                }

            } else if (type.equals(MessageType.RIDE_REQUEST.name())) {
                
                int pId = doc.getInt("passengerId");
                double srcLat = doc.getDouble("srcLat");
                double srcLon = doc.getDouble("srcLon");
                
                
                
                
                JSONObject dsReq = new JSONObject()
                    .put("type", "FIND_DRIVERS")
                    .put("lat", srcLat)
                    .put("lon", srcLon)
                    .put("radius", 5.0); 
                
                driverServiceClient.send(dsReq);
                JSONObject dsRes = driverServiceClient.receive();
                JSONArray drivers = dsRes.optJSONArray("drivers");
                
                
                boolean assigned = false;
                if (drivers != null) {
                    for (int i=0; i<drivers.length(); i++) {
                        int driverId = drivers.getInt(i);
                        if (RideManager.getInstance().attemptAssignMessage(pId, driverId, srcLat, srcLon, doc.getDouble("destLat"), doc.getDouble("destLon"))) {
                            assigned = true;
                            
                            
                            dbClient.send(new JSONObject()
                                .put("type", "DB_QUERY")
                                .put("sql", "SELECT u.username, d.vehicle_model, d.license_plate FROM users u JOIN drivers d ON u.id = d.user_id WHERE u.id = " + driverId));
                            JSONObject dInfo = dbClient.receive();
                            String dName = "Driver #" + driverId; 
                            String dCar = "Generic Car"; 
                            String dPlate = "---"; 

                            if(dInfo.has("data")) {
                                JSONArray arr = dInfo.optJSONArray("data");
                                if(arr != null && arr.length() > 0) {
                                    JSONObject r = arr.getJSONObject(0);
                                    if(r.has("username")) dName = r.getString("username");
                                    if(r.has("vehicle_model")) dCar = r.getString("vehicle_model");
                                    if(r.has("license_plate")) dPlate = r.getString("license_plate");
                                }
                            }

                            
                             out.println(new JSONObject()
                                .put("type", "RIDE_UPDATE")
                                .put("status", "ASSIGNED")
                                .put("driverId", driverId)
                                .put("driverName", dName)
                                .put("driverCar", dCar)
                                .put("driverPlate", dPlate)
                                .toString());
                             
                             
                             dbClient.send(new JSONObject()
                                 .put("type", "DB_UPDATE")
                                 .put("sql", "INSERT INTO rides (passenger_id, driver_id, pickup_lat, pickup_lon, dest_lat, dest_lon, status) VALUES (" 
                                     + pId + "," + driverId + "," + srcLat + "," + srcLon + "," + doc.getDouble("destLat") + "," + doc.getDouble("destLon") + ",'ASSIGNED')"));
                             
                             
                             JSONObject dbResp = dbClient.receive();
                             int rideId = dbResp.has("generatedId") ? dbResp.getInt("generatedId") : -1;

                            
                            JSONObject notifyMsg = new JSONObject()
                                .put("type", "NOTIFY_DRIVER")
                                .put("driverId", driverId)
                                .put("rideId", rideId)  
                                .put("passengerId", pId) 
                                .put("message", "New Ride Assigned! Pickup: " + srcLat + "," + srcLon);
                            driverServiceClient.send(notifyMsg);
                             
                            break;
                        }
                    }
                }
                
                if (!assigned) {
                    out.println(new JSONObject().put("type", "RIDE_UPDATE").put("status", "NO_DRIVERS_FOUND").toString());
                }

            } else if (type.equals("CHECK_RIDE_STATUS")) {
                 int pId = doc.getInt("userId");
                 
                 String sql = "SELECT id, status, driver_id FROM rides WHERE passenger_id=" + pId + " ORDER BY id DESC LIMIT 1";
                 dbClient.send(new JSONObject().put("type", "DB_QUERY").put("sql", sql));
                 JSONObject res = dbClient.receive();
                 JSONArray data = res.optJSONArray("data");
                 
                 if (data != null && data.length() > 0) {
                     JSONObject ride = data.getJSONObject(0);
                     out.println(new JSONObject()
                        .put("type", "RIDE_UPDATE")
                        .put("status", ride.getString("status"))
                        .put("driverId", ride.getInt("driver_id"))
                        .put("rideId", ride.getInt("id"))
                        .toString());
                 } else {
                     out.println(new JSONObject().put("type", "RIDE_UPDATE").put("status", "NONE").toString());
                 }

            } else if (type.equals("RATE_DRIVER")) {
                int driverId = doc.getInt("driverId");
                double rating = doc.getDouble("rating");
                
                
                dbClient.send(new JSONObject().put("type", "DB_QUERY").put("sql", "SELECT rating FROM users WHERE id=" + driverId));
                JSONObject res = dbClient.receive();
                double oldRating = 5.0;
                JSONArray data = res.optJSONArray("data");
                if (data != null && data.length() > 0) {
                    oldRating = data.getJSONObject(0).optDouble("rating", 5.0);
                }
                
                
                double finalRating = ((oldRating * 5.0) + rating) / 6.0;
                if (finalRating > 5.0) finalRating = 5.0;
                if (finalRating < 1.0) finalRating = 1.0;
                
                dbClient.send(new JSONObject()
                    .put("type", "DB_UPDATE")
                    .put("sql", String.format((java.util.Locale)null, "UPDATE users SET rating = %f WHERE id=%d", finalRating, driverId)));
                dbClient.receive(); 
                
                out.println(new JSONObject().put("success", true).toString());

            } else if (type.equals("ADMIN_STATS")) {
                 int activeRides = RideManager.getInstance().getActiveRideCount();
                 out.println(new JSONObject().put("activeRides", activeRides).toString());
            
            } else if (type.equals("ADMIN_GET_SYSTEM_DATA")) {
                
                int activeRides = RideManager.getInstance().getActiveRideCount();
                
                
                
                String sql = "SELECT u.id, u.username, u.role, u.rating, u.is_blocked, d.vehicle_model, d.license_plate, d.status " +
                             "FROM users u LEFT JOIN drivers d ON u.id = d.user_id";
                
                dbClient.send(new JSONObject()
                    .put("type", "DB_QUERY")
                    .put("sql", sql));
                JSONObject dbRes = dbClient.receive();
                
                JSONArray users = dbRes.has("data") ? (JSONArray) dbRes.get("data") : new JSONArray();
                
                out.println(new JSONObject()
                    .put("type", "ADMIN_DATA_RESPONSE")
                    .put("activeRides", activeRides)
                    .put("users", users)
                    .toString());
            
            } else if (type.equals("ADMIN_APPROVE_DRIVER")) {
                int driverId = doc.getInt("driverId");
                boolean approved = doc.getBoolean("approved");
                String newStatus = approved ? "APPROVED" : "REJECTED";
                
                System.out.println("[Admin] Setting driver " + driverId + " status to " + newStatus);
                
                String sql = "UPDATE drivers SET status = '" + newStatus + "' WHERE user_id = " + driverId;
                
                dbClient.send(new JSONObject()
                    .put("type", "DB_UPDATE")
                    .put("sql", sql));
                JSONObject updateRes = dbClient.receive(); 
                
                
                int rows = 0;
                if (updateRes.has("rowsAffected")) {
                    Object ra = updateRes.get("rowsAffected");
                    if (ra instanceof Number) rows = ((Number)ra).intValue();
                }

                if (rows == 0) {
                     System.out.println("[Admin] Driver record missing for " + driverId + ". Creating default record.");
                     String insertSql = String.format("INSERT INTO drivers (user_id, vehicle_model, license_plate, status) VALUES (%d, 'Unknown', 'Unknown', '%s')", driverId, newStatus);
                     dbClient.send(new JSONObject().put("type", "DB_UPDATE").put("sql", insertSql));
                     dbClient.receive();
                }
                
                out.println(new JSONObject().put("success", true).toString());

            } else if (type.equals("ADMIN_BLOCK_DRIVER")) {
                int driverId = doc.getInt("driverId");
                boolean block = doc.optBoolean("block", true); 
                int val = block ? 1 : 0;
                
                dbClient.send(new JSONObject()
                    .put("type", "DB_UPDATE")
                    .put("sql", "UPDATE users SET is_blocked = " + val + " WHERE id = " + driverId));
                dbClient.receive(); 
                out.println(new JSONObject().put("success", true).toString());

            } else if (type.equals("ADMIN_DELETE_USER")) {
                int userId = doc.getInt("userId");
                
                
                
                dbClient.send(new JSONObject()
                    .put("type", "DB_UPDATE")
                    .put("sql", "DELETE FROM users WHERE id = " + userId));
                dbClient.receive(); 
                out.println(new JSONObject().put("success", true).toString());
            }
        }
    }
}
