package com.rideshare.dispatch;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;


public class RideManager {
    private static RideManager instance;
    
    
    private final ConcurrentHashMap<Integer, ReentrantLock> driverLocks = new ConcurrentHashMap<>();
    
    
    private final ConcurrentHashMap<Integer, String> activeRides = new ConcurrentHashMap<>(); 
    
    // Tracks occupied drivers: DriverID -> RideID
    private final ConcurrentHashMap<Integer, Integer> driverToRideMap = new ConcurrentHashMap<>();

    private RideManager() {}

    public static synchronized RideManager getInstance() {
        if (instance == null) instance = new RideManager();
        return instance;
    }

    
    public boolean attemptAssignMessage(int passengerId, int driverId, double srcLat, double srcLon, double destLat, double destLon) {
        ReentrantLock lock = driverLocks.computeIfAbsent(driverId, k -> new ReentrantLock());
        
        if (lock.tryLock()) {
            try {
                // If driver is currently active in another ride, prevent dual assignment
                if (driverToRideMap.containsKey(driverId)) {
                    System.out.println("Driver " + driverId + " is already occupied in activeRide.");
                    return false;
                }
                
                System.out.println("Locked Driver " + driverId + " for Passenger " + passengerId);
                return true;
                
            } finally {
                lock.unlock();
            }
        } else {
            System.out.println("Driver " + driverId + " is currently busy/locked.");
        }
        
        return false;
    }
    
    public void addActiveRide(int rideId, int driverId, String status) {
        activeRides.put(rideId, status);
        driverToRideMap.put(driverId, rideId);
    }
    
    public void removeActiveRide(int rideId) {
        activeRides.remove(rideId);
        driverToRideMap.entrySet().removeIf(entry -> entry.getValue().equals(rideId));
    }
    
    public int getActiveRideCount() {
        return activeRides.size();
    }
}

