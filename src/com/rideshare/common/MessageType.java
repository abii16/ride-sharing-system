package com.rideshare.common;

public enum MessageType {
    
    AUTH_LOGIN,
    AUTH_REGISTER,
    AUTH_RESPONSE,
    
    
    DRIVER_GO_ONLINE,
    DRIVER_GO_OFFLINE,
    LOCATION_UPDATE,
    
    
    RIDE_REQUEST,
    RIDE_OFFER,
    RIDE_ACCEPT,
    RIDE_REJECT,
    RIDE_START,
    RIDE_COMPLETE,
    RIDE_CANCEL,
    RIDE_UPDATE, 
    
    
    ADMIN_GET_STATS,
    ADMIN_BLOCK_DRIVER,
    ADMIN_GET_LOGS,
    
    
    DB_QUERY,
    DB_UPDATE,
    DB_RESPONSE,
    
    
    ERROR,

    // Simple health-check style request/response
    PING,
    PONG
}
