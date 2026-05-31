# 🛡️ Enterprise Security Operations Center (SOC) & Cryptography Report

This document details the security improvements implemented in the **Distributed Ride-Sharing System** to align with the **Course Learning Outcomes (CLOs)** of your Computer Security course. 

The project has been fortified with a **Multilayer Defense Architecture** including a custom **Web Application Firewall (WAF)**, a real-time **Intrusion Detection System (IDS)**, **SHA-256 password salting & hashing**, **AES-256 database column encryption**, and an interactive **Security SOC Control Panel** with a **Live Penetration Testing Sandbox** for your teacher to evaluate.

---

## 🎯 Course Learning Outcomes (CLO) Alignment Matrix

| Course Learning Outcome | Secure System Implementation & Features | Code Files & References |
| :--- | :--- | :--- |
| **CLO1: Describe Security Foundations** | Multilayered defensive system designed from scratch, providing visual evidence of network intrusion blocking, sanitization, and alert response mechanisms. | `com.rideshare.web.WebGateway` <br> `com.rideshare.security.SecurityMonitor` |
| **CLO2: Threat & Vulnerability Analysis** | Structured regex-based detection systems to analyze incoming JSON payloads for SQL Injection vectors and Cross-Site Scripting (XSS) attacks. | `SecurityMonitor.java` (in `com.rideshare.security`) |
| **CLO3: Secure Software & Testing** | Built an interactive **Penetration Testing Sandbox** directly into the Admin dashboard. Allows testing of system defenses in real-time, displaying blocked threat reports. | `admin.html` (Lines 120-195) <br> `admin.js` (Lines 220-290) |
| **CLO4: State-of-the-Art Security Tools** | Custom-built WAF and IDS modules matching modern web system controls (e.g. rate-limiting, brute-force lockout, and incident event logs). | `SecurityMonitor.java` (in `com.rideshare.security`) <br> `WebGateway.java` (Register/Login Handlers) |
| **CLO5: Device & Server Security Evaluation** | Configured decoupled boundaries. The Web Gateway serves as a buffer; clients cannot query MySQL directly. Unauthenticated payloads are intercepted before reaching state nodes. | `WebGateway.java` <br> `DatabaseService.java` |
| **CLO6: Security Architectures & Methodologies** | Multi-tiered validation: WAF (network edge) ➔ Dispatch validation (application layer) ➔ Salted/Encrypted write (persistence layer). | `WebGateway.java` ➔ `DispatchServer.java` ➔ `DatabaseService.java` |
| **CLO7: Develop New Software Security Tools** | Engineered a custom Java-based WAF/IDS framework that records blocked threats into the MySQL `audit_logs` table under standard formats (`SQL_INJECTION_BLOCKED`, `XSS_BLOCKED`). | `SecurityMonitor.java` <br> `audit_logs` MySQL Table |
| **CLO8: Advanced Cryptographic Algorithms** | Applied two industry-standard advanced algorithms: <br>1. **SHA-256 with Salt** (User passwords) <br>2. **AES-256 Symmetric Encryption** (PII data: user email & license plate). | `com.rideshare.security.CryptoUtil` <br> `DispatchServer.java` (Lines 135-250) |

---

## 🔑 Implementation Details (CLO8: Cryptography)

Two advanced cryptographic algorithms are applied using the custom `com.rideshare.security.CryptoUtil` class:

### 1. Advanced Password Hashing (SHA-256 + 128-bit Cryptographic Salt)
Instead of storing passwords in plain text, the system uses a **Cryptographic Salt** generated per user via `SecureRandom`. The password is combined with the salt and hashed using `SHA-256`. 
* **Database Format**: `salt:hash` (e.g. `e1f82c...:c5a3d7...`)
* **Benefit**: Protects against precomputed **Rainbow Table** attacks and dictionary attacks. If two users have the same password, their hashes are completely different due to unique salts.

### 2. Symmetric Data Encryption (AES-256-CBC)
To protect **Personal Identifiable Information (PII)** under modern data regulations (e.g., GDPR), sensitive database columns are encrypted at rest using **AES-256** in Cipher Block Chaining (CBC) mode with `PKCS5Padding`.
* **Data Encrypted**: User `email` (in `users` table) and Driver `license_plate` (in `drivers` table).
* **Format in Database**: Prefixed with `AES256:` (e.g. `AES256:i2WqXj7qLd...`) to allow secure identification and fallback.
* **Decryption**: Automatically decrypted *only* at the Dispatch Server layer before serving to authenticated clients (like system admins during dashboard view).

---

## 🛡️ Web Application Firewall (WAF) & Intrusion Detection System (IDS)

The `com.rideshare.security.SecurityMonitor` class handles threat mitigation and works as a gateway firewall.

### 1. SQL Injection (SQLi) Prevention
Detects common SQL injection keywords and structures like:
* `' OR '1'='1` or `' OR 1=1 --`
* `UNION SELECT` / `SELECT * FROM`
* `; DROP TABLE`
If a SQLi payload is detected in any input (username, password, email, license plate), the WAF immediately aborts the connection, returns `403 Forbidden` with a security alert, and logs the incident to the database.

### 2. Cross-Site Scripting (XSS) Prevention
Scans for HTML and JavaScript injections designed to steal cookies or hijack sessions:
* `<script>...</script>`
* `javascript:` protocol URLs
* Interactive attributes like `onload=`, `onerror=`, `onclick=`
Blocks the registration/login flow instantly if any XSS payload is detected.

### 3. Brute-Force Login Protection (Account Lockout)
Tracks failed login attempts dynamically in a `ConcurrentHashMap`:
* Allows up to **5 failed login attempts** per username.
* On the 5th failure, the account is temporarily **Locked Out** (locked at the firewall level).
* Logs a `BRUTE_FORCE_ALERT` to the database, allowing system admins to investigate.

---

## 📊 Interactive Security SOC Control Panel (CLO3)

An interactive **Security Operations Center (SOC)** has been added to the Admin Panel (`pages/admin.html`):

### 🌟 Key Interactive Features:

1. **Active Security Toggles**: 
   The Admin can dynamically toggle **Web Application Firewall**, **Brute-Force Shield**, or **Database Encryption** ON/OFF in real-time. Toggling these settings generates `CONFIG_CHANGE` log records.
2. **Penetration Sandbox Test**:
   Allows your teacher to **simulate an attack** with one click:
   * **SQL Injection Simulation**: Launches an attack using `' OR '1'='1` against the `/api/login` endpoint.
   * **XSS Script Injection Simulation**: Launches an attack using `<script>alert(1)</script>` inside `/api/register`.
   * **Brute Force Spray Simulation**: Sprays 5 rapid failed login requests against a mock passenger account.
   The sandbox dashboard prints a live feed of the attack, showing **WAF response headers**, **Status Codes**, and the WAF's **Incident Mitigation Block Report**.
3. **Real-time IDS Incident Logs Table**:
   Queries the `audit_logs` table instantly to show all blocked threats, timestamps, and precise payload details!

---

## 🚀 How to Run the System & Demonstrate to Your Teacher

### Step 1: Start the System
Ensure MySQL is running (e.g., in XAMPP) and run `start_all.bat`. It compiles all security additions automatically and starts the 4 microservices.

### Step 2: Open the Admin Portal
1. Open your browser and navigate to: [http://localhost:8080/pages/admin.html](http://localhost:8080/pages/admin.html)
2. Log in using the default admin credentials:
   * **Username**: `admin`
   * **Secure Key**: `Admin123!`

### Step 3: Open the Security Center Tab
1. Click on the **🛡️ Security Center** menu item on the left sidebar (glowing green).
2. Show your teacher the active shields: WAF, Brute Force protection, and AES-256 standard indicators.

### Step 4: Run the Attack Simulation
1. Scroll to the **Vulnerability Penetration Sandbox** panel.
2. Select **SQL Injection Attack** and click **"Launch Attack Simulation"**.
3. Watch the system instantly intercept it, return a `403 Forbidden`, and display the blocked threat log.
4. Check the **Real-time IDS Incident Logs** table below to see the attack recorded with full detail!
5. Repeat for **XSS Injection** and **Brute Force** to show all defensive layers in action!

---

> [!TIP]
> **Viva Defense Tip**: Explain to your teacher that the SQL database is secured against SQL injection *both* at the **Web Gateway** (WAF level) and in code (using input sanitization via `SecurityMonitor.sanitize()`), satisfying industry standard **Defense-in-Depth** principles. The database column encryption proves your compliance with modern security compliance models!
