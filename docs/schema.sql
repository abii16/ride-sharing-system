


CREATE DATABASE IF NOT EXISTS rideshare_db;
USE rideshare_db;


CREATE TABLE IF NOT EXISTS users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(120),
    password_hash VARCHAR(255) NOT NULL, 
    role ENUM('PASSENGER', 'DRIVER', 'ADMIN') NOT NULL,
    rating DOUBLE DEFAULT 5.0,
    is_blocked BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Backward-compatible migrations (XAMPP uses MariaDB, which supports IF NOT EXISTS)
ALTER TABLE users ADD COLUMN IF NOT EXISTS email VARCHAR(120);
ALTER TABLE users ADD COLUMN IF NOT EXISTS rating DOUBLE DEFAULT 5.0;


CREATE TABLE IF NOT EXISTS drivers (
    user_id INT PRIMARY KEY,
    vehicle_model VARCHAR(50),
    license_plate VARCHAR(20) UNIQUE,
    status ENUM('PENDING', 'APPROVED', 'REJECTED') DEFAULT 'PENDING',
    is_online BOOLEAN DEFAULT FALSE,
    last_lat DOUBLE,
    last_lon DOUBLE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);


CREATE TABLE IF NOT EXISTS rides (
    id INT AUTO_INCREMENT PRIMARY KEY,
    passenger_id INT NOT NULL,
    driver_id INT, 
    pickup_lat DOUBLE NOT NULL,
    pickup_lon DOUBLE NOT NULL,
    dest_lat DOUBLE NOT NULL,
    dest_lon DOUBLE NOT NULL,
    status ENUM('REQUESTED', 'ASSIGNED', 'STARTED', 'COMPLETED', 'CANCELLED') DEFAULT 'REQUESTED',
    fare DECIMAL(10,2),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (passenger_id) REFERENCES users(id),
    FOREIGN KEY (driver_id) REFERENCES users(id)
);


CREATE TABLE IF NOT EXISTS audit_logs (
    id INT AUTO_INCREMENT PRIMARY KEY,
    event_type VARCHAR(50),
    details TEXT,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);


INSERT IGNORE INTO users (username, password_hash, role) VALUES ('admin', 'admin123', 'ADMIN');
INSERT IGNORE INTO users (username, password_hash, role) VALUES ('abel', '123', 'DRIVER');
INSERT IGNORE INTO users (username, password_hash, role) VALUES ('yoni', '123', 'PASSENGER');

INSERT INTO drivers (user_id, vehicle_model, license_plate, status)
VALUES (2, 'Toyota Prius', 'ABC-123', 'APPROVED')
ON DUPLICATE KEY UPDATE
    vehicle_model = VALUES(vehicle_model),
    license_plate = VALUES(license_plate),
    status = 'APPROVED';
