# Distributed Ride-Sharing System (Java)

A university-grade distributed system extending a basic ride-sharing architecture.
This project uses **Raw TCP Sockets** and **JSON** for inter-process communication (IPC), strictly avoiding HTTP/REST or Spring Boot to demonstrate low-level distributed systems concepts.

## 🏗 System Architecture

The system consists of 3 independent server nodes (JVMs) and 3 client types:

1.  **Database Service (Port 7000):** The only service with access to MySQL.
2.  **Dispatch Server (Port 5000):** Handles ride matching logic and concurrency.
3.  **Driver Service (Port 6000):** Manages persistent driver connections and real-time location.

## 🛠 Prerequisites

*   **Java JDK 8+**
*   **MySQL Server** (XAMPP or standalone)
* org.json` library) *Must be added to Classpath*

## 🚀 Setup & Execution Guide

### Step 1: Database Setup
1.  Start MySQL (e.g., via XAMPP Control Panel).
2.  Run the SQL script located at `docs/schema.sql` to create the database and seed users.

### Step 2: Compile
Compile all files. Ensure the `org.json` library is in your classpath.
```bash
javac -cp "lib/json.jar;src" src/com/rideshare/database/DatabaseService.java
javac -cp "lib/json.jar;src" src/com/rideshare/dispatch/DispatchServer.java
javac -cp "lib/json.jar;src" src/com/rideshare/driver/DriverService.java
```

### Step 3: Run Services (In this specific order)

Usually, just run `start_all.bat` automatically.

**Terminal 1: Database Service**
```bash
java -cp "lib/json.jar;lib/mysql-connector.jar;src" com.rideshare.database.DatabaseService
```

**Terminal 2: Driver Service**
```bash
java -cp "lib/json.jar;src" com.rideshare.driver.DriverService
```

**Terminal 3: Dispatch Server**
```bash
java -cp "lib/json.jar;src" com.rideshare.dispatch.DispatchServer
```

### Step 4: Run Web Clients

Open your browser to:
`http://localhost:8080`

## 🧠 Key Features for Viva Defense

1.  **Distributed State:** Driver locations are in `DriverService` memory (fast), Ride Lifecycle is in `DispatchServer` memory + DB.
2.  **Concurrency:** `RideManager` uses `ReentrantLock` to prevent double-booking drivers.
3.  **Real-Time:** Uses `DriverService` -> `DispatchServer` -> `WebGateway` push notifications.
