package com.rideshare.driver;

import com.rideshare.common.NetworkClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;


public class DriverService {
    private static int PORT = 6000;
    
    
    public static final ConcurrentHashMap<Integer, double[]> driverLocations = new ConcurrentHashMap<>();
    
    public static final ConcurrentHashMap<Integer, PrintWriter> onlineDrivers = new ConcurrentHashMap<>();
    
    public static final ConcurrentHashMap<Integer, Boolean> driverAvailability = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        if (args.length > 0) PORT = Integer.parseInt(args[0]);

        System.out.println("Starting Driver Service on port " + PORT + "...");
        
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(new DriverHandler(socket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class DriverHandler implements Runnable {
        private Socket socket;
        private int driverId = -1;

        public DriverHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                String line;
                while ((line = in.readLine()) != null) {
                    JSONObject msg = new JSONObject(line);
                    String type = msg.optString("type", "");

                    if (type.equals("REGISTER_DRIVER_CONN")) {
                        
                        this.driverId = msg.getInt("driverId");
                        onlineDrivers.put(driverId, out);
                        
                        driverLocations.put(driverId, new double[]{0.0, 0.0}); 
                        
                        driverAvailability.put(driverId, true);
                        System.out.println("Driver " + driverId + " is ONLINE.");

                    } else if (type.equals("DRIVER_STATUS")) {
                        if (driverId != -1) {
                            boolean isOnline = msg.getBoolean("status"); 
                            driverAvailability.put(driverId, isOnline);
                            System.out.println("Driver " + driverId + " status changed to: " + (isOnline ? "AVAILABLE" : "UNAVAILABLE"));
                        }

                    } else if (type.equals("LOCATION_UPDATE")) {
                        if (driverId != -1) {
                            double lat = msg.getDouble("lat");
                            double lon = msg.getDouble("lon");
                            driverLocations.put(driverId, new double[]{lat, lon});
                        }

                    } else if (type.equals("FIND_DRIVERS")) {
                        
                        
                        // double reqLat = msg.getDouble("lat"); // unused (simple matchmaking)
                        // double reqLon = msg.getDouble("lon"); // unused
                        // double radius = msg.getDouble("radius"); // unused
                        
                        
                        JSONArray drivers = new JSONArray();
                        for (Integer id : onlineDrivers.keySet()) {
                            
                            if (driverAvailability.getOrDefault(id, false)) {
                                drivers.put(id); 
                            }
                        }
                        
                        JSONObject response = new JSONObject();
                        response.put("drivers", drivers);
                        out.println(response.toString());

                    } else if (type.equals("NOTIFY_DRIVER")) {
                        
                        int targetDriverId = msg.getInt("driverId");
                        PrintWriter driverOut = onlineDrivers.get(targetDriverId);
                        if (driverOut != null) {
                            JSONObject pushMsg = new JSONObject()
                                .put("type", "RIDE_OFFER")
                                .put("rideId", msg.has("rideId") ? msg.getInt("rideId") : -1)
                                .put("passengerId", msg.has("passengerId") ? msg.getInt("passengerId") : -1)
                                .put("message", msg.getString("message"));
                            driverOut.println(pushMsg.toString());
                        }

                    } else if (type.equals("RIDE_COMPLETE")) {
                        int rideId = msg.getInt("rideId");
                        System.out.println("Driver " + driverId + " completing ride " + rideId);
                        
                        
                        try (NetworkClient db = new NetworkClient("localhost", 7000)) {
                            db.connect();
                            db.send(new JSONObject()
                                .put("type", "DB_UPDATE")
                                .put("sql", "UPDATE rides SET status='COMPLETED' WHERE id=" + rideId));
                            db.receive();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            } catch (Exception e) {
                
            } finally {
                if (driverId != -1) {
                    onlineDrivers.remove(driverId);
                    driverLocations.remove(driverId);
                    driverAvailability.remove(driverId);
                    System.out.println("Driver " + driverId + " went OFFLINE.");
                }
            }
        }
    }
}
