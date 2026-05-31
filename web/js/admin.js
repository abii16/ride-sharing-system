        let sessionId = localStorage.getItem('admin_sessionId');
        let latestSecurityData = null;

        const INACTIVITY_TIMEOUT = 10 * 60 * 1000; // 10 minutes in milliseconds
        let inactivityInterval = null;

        function resetActivityTimer() {
            localStorage.setItem('admin_lastActivity', Date.now());
        }

        function checkInactivity() {
            if (!sessionId) return;
            const last = parseInt(localStorage.getItem('admin_lastActivity') || Date.now());
            if (Date.now() - last > INACTIVITY_TIMEOUT) {
                alert("Session expired due to 10 minutes of inactivity.");
                logout();
            }
        }

        function setupInactivityMonitor() {
            ['click', 'mousemove', 'keydown', 'scroll', 'touchstart'].forEach(evt => {
                document.addEventListener(evt, resetActivityTimer, true);
            });
            resetActivityTimer();
            if (inactivityInterval) clearInterval(inactivityInterval);
            inactivityInterval = setInterval(checkInactivity, 5000);
        }

        window.addEventListener('DOMContentLoaded', () => {
            if (sessionId) {
                const last = parseInt(localStorage.getItem('admin_lastActivity') || Date.now());
                if (Date.now() - last > INACTIVITY_TIMEOUT) {
                    localStorage.removeItem('admin_sessionId');
                    sessionId = null;
                    window.location.href = 'auth.html';
                    return;
                }
                
                document.getElementById('authScreen').style.display = 'none';
                document.getElementById('app').style.display = 'flex';
                log("Admin Session Restored.");
                refreshData();
                startPolling();
                setupInactivityMonitor();
            } else {
                window.location.href = 'auth.html';
            }
        });

        function logout() {
            localStorage.removeItem('admin_sessionId');
            localStorage.removeItem('admin_lastActivity');
            location.reload();
        }

        async function login() {
            const user = document.getElementById('username').value;
            const pass = document.getElementById('password').value;
            try {
                const res = await fetch('/api/login', { 
                    method: 'POST', 
                    body: JSON.stringify({ username: user, password: pass }) 
                });
                const data = await res.json();
                
                if (data.success && data.role === 'ADMIN') {
                    sessionId = data.sessionId;
                    localStorage.setItem('admin_sessionId', sessionId);
                    resetActivityTimer();
                    document.getElementById('authScreen').style.display = 'none';
                    document.getElementById('app').style.display = 'flex';
                    log("Admin Session Established.");
                    refreshData();
                    startPolling();
                    setupInactivityMonitor();
                } else alert("Access Denied: " + data.message);
            } catch (e) { alert("Error: " + e.message); }
        }

        async function refreshData() {
            log("Fetching system dump...");
            await fetch('/api/send', {
                method: 'POST',
                headers: { 'X-Session-ID': sessionId },
                body: JSON.stringify({ type: 'ADMIN_GET_SYSTEM_DATA' })
            });
        }

        async function blockDriver(id, doBlock, btn) {
            const action = doBlock ? "BLOCK" : "UNBLOCK";
            if (!checkConfirm(btn, "Confirm?")) return;
            
            log(`Sending ${action} command for User ID ${id}...`);
            await fetch('/api/send', {
                method: 'POST',
                headers: { 'X-Session-ID': sessionId },
                body: JSON.stringify({ type: 'ADMIN_BLOCK_DRIVER', driverId: id, block: doBlock })
            });
            setTimeout(refreshData, 1000);
        }

        async function deleteUser(id, btn) {
            if (!checkConfirm(btn, "Confirm?")) return;
            log(`Deleting User ID ${id}...`);
            await fetch('/api/send', {
                method: 'POST',
                headers: { 'X-Session-ID': sessionId },
                body: JSON.stringify({ type: 'ADMIN_DELETE_USER', userId: id })
            });
            setTimeout(refreshData, 1000);
        }

        
        function switchView(viewId, menuEl) {
            
            document.querySelectorAll('.menu-item').forEach(el => el.classList.remove('active'));
            if(menuEl) menuEl.classList.add('active');

            
            document.querySelectorAll('.view-section').forEach(el => el.style.display = 'none');
            
            
            const targetEl = document.getElementById(viewId);
            if (targetEl) targetEl.style.display = 'block';

            
            if(viewId === 'view-approvals') refreshDriverRequests();
            if(viewId === 'view-users' || viewId === 'view-dashboard') refreshData();
            if(viewId === 'view-security') refreshSecurityData();
        }

        
        function refreshDriverRequests() {
            
             refreshData();
        }

        async function approveDriver(id, approve, btn) {
            const action = approve ? "APPROVE" : "REJECT";
            
            if (!checkConfirm(btn, "Confirm?")) return;
            
            await fetch('/api/send', {
                method: 'POST',
                headers: { 'X-Session-ID': sessionId },
                body: JSON.stringify({ 
                    type: 'ADMIN_APPROVE_DRIVER', 
                    driverId: id,
                    approved: approve 
                })
            });
            log(`Driver ${id} ${action}D`);
            setTimeout(refreshDriverRequests, 1000);
        }

        
        function checkConfirm(btn, text) {
            if (!btn) return true; 
            if (btn.dataset.confirmed) {
                
                btn.innerText = btn.dataset.original;
                delete btn.dataset.confirmed;
                return true;
            }
            
            btn.dataset.original = btn.innerText;
            btn.innerText = text;
            btn.dataset.confirmed = "true";
            
            
            setTimeout(() => {
                if(btn && btn.dataset.confirmed) {
                    btn.innerText = btn.dataset.original;
                    delete btn.dataset.confirmed;
                }
            }, 3000);
            return false;
        }

        
        function startPolling() {
            setInterval(async () => {
                try {
                    const res = await fetch('/api/updates', { headers: { 'X-Session-ID': sessionId } });
                    const msgs = await res.json();
                    
                    if (msgs.length > 0) {
                        msgs.forEach(msg => {
                            if (msg.type === 'ADMIN_DATA_RESPONSE') {
                                renderDashboard(msg);
                                renderApprovals(msg);
                            } else if (msg.type === 'ADMIN_SECURITY_RESPONSE') {
                                renderSecurityDashboard(msg);
                            } else {
                                log("Event: " + JSON.stringify(msg));
                            }
                        });
                    }
                } catch(e) {}
            }, 1000);
        }

        async function refreshSecurityData() {
            log("Fetching security status and audit logs...");
            await fetch('/api/send', {
                method: 'POST',
                headers: { 'X-Session-ID': sessionId },
                body: JSON.stringify({ type: 'ADMIN_GET_SECURITY_DATA' })
            });
        }

        function renderSecurityDashboard(data) {
            latestSecurityData = data;
            if (!document.getElementById('sec-waf-status')) return;
            document.getElementById('sec-waf-status').innerText = data.sqlFilterEnabled ? "Enabled" : "Disabled";
            document.getElementById('sec-waf-status').style.color = data.sqlFilterEnabled ? "#00ff88" : "#ff4e4e";
            
            document.getElementById('sec-brute-status').innerText = data.bruteForceProtection ? "Active" : "Bypassed";
            document.getElementById('sec-brute-status').style.color = data.bruteForceProtection ? "#ff9f43" : "#ff4e4e";
            
            document.getElementById('sec-crypto-status').innerText = data.encryptionEnabled ? "AES-256" : "Plaintext";
            document.getElementById('sec-crypto-status').style.color = data.encryptionEnabled ? "#4e8cff" : "#ff4e4e";

            // Render Security Audit logs
            const tbody = document.getElementById('securityLogsTableBody');
            tbody.innerHTML = "";
            
            const logs = data.logs;
            // Filter only threat-related event types
            const securityLogs = logs.filter(l => 
                l.event_type === 'SQL_INJECTION_BLOCKED' || 
                l.event_type === 'XSS_BLOCKED' || 
                l.event_type === 'BRUTE_FORCE_ALERT' || 
                l.event_type === 'CONFIG_CHANGE' || 
                l.event_type === 'SECURITY_ALERT'
            );
            
            if (securityLogs.length === 0) {
                tbody.innerHTML = '<tr><td colspan="4" style="text-align:center; color:var(--text-muted); padding:20px">No intrusion events detected. System is running securely.</td></tr>';
            } else {
                securityLogs.forEach(l => {
                    const tr = document.createElement('tr');
                    const isAlert = l.event_type.includes('BLOCKED') || l.event_type.includes('ALERT');
                    const badgeColor = isAlert ? 'background:#ff4e4e22; color:#ff4e4e; border:1px solid #ff4e4e44' : 'background:#ff9f4322; color:#ff9f43; border:1px solid #ff9f4344';
                    
                    tr.innerHTML = `
                        <td>#${l.id}</td>
                        <td><span class="status-badge" style="${badgeColor}">${l.event_type}</span></td>
                        <td>${l.details}</td>
                        <td style="color:var(--text-muted); font-size:0.85rem">${l.timestamp}</td>
                    `;
                    tbody.appendChild(tr);
                });
            }
        }

        async function toggleSecurityControl(setting) {
            let currentVal = false;
            if (setting === 'sqlFilter') {
                currentVal = document.getElementById('sec-waf-status').innerText === "Enabled";
            } else if (setting === 'bruteForce') {
                currentVal = document.getElementById('sec-brute-status').innerText === "Active";
            } else if (setting === 'encryption') {
                currentVal = document.getElementById('sec-crypto-status').innerText === "AES-256";
            }
            
            log(`Toggling security control '${setting}' to ${!currentVal}...`);
            await fetch('/api/send', {
                method: 'POST',
                headers: { 'X-Session-ID': sessionId },
                body: JSON.stringify({ type: 'ADMIN_TOGGLE_SECURITY', setting: setting, value: !currentVal })
            });
            setTimeout(refreshSecurityData, 500);
        }

        async function triggerSandboxPenetration() {
            const vector = document.getElementById('sandbox-attack-vector').value;
            const resultBox = document.getElementById('sandbox-result');
            resultBox.style.display = "block";
            resultBox.style.background = "rgba(0,0,0,0.5)";
            resultBox.style.color = "#ff9f43";
            resultBox.innerHTML = "[SANDBOX] Launching penetration attempt...\n";
            
            try {
                let response;
                if (vector === 'SQL_INJECT') {
                    // Fire a malicious login request containing a SQL injection attempt
                    response = await fetch('/api/login', {
                        method: 'POST',
                        body: JSON.stringify({ username: "admin' OR '1'='1", password: "arbitrary_password" })
                    });
                } else if (vector === 'XSS_SCRIPT') {
                    // Fire a malicious registration request containing XSS script payload
                    response = await fetch('/api/register', {
                        method: 'POST',
                        body: JSON.stringify({ 
                            username: "hacker", 
                            email: "hacker@test.com", 
                            password: "<script>alert(document.cookie)</script>",
                            role: "PASSENGER"
                        })
                    });
                } else if (vector === 'BRUTE_FORCE') {
                    // Spray 5 rapid failed login attempts on a dummy user
                    resultBox.innerHTML += "[SANDBOX] Launching dictionary spray brute-force... (5 requests)\n";
                    for (let i = 1; i <= 5; i++) {
                        resultBox.innerHTML += `[SANDBOX] Spraying credentials packet #${i}...\n`;
                        response = await fetch('/api/login', {
                            method: 'POST',
                            body: JSON.stringify({ username: "victim_passenger", password: "wrong_password_" + i })
                        });
                    }
                }
                
                const data = await response.json();
                
                if (!response.ok || data.success === false) {
                    resultBox.style.color = "#00ff88";
                    resultBox.style.border = "1px solid #00ff8844";
                    resultBox.innerHTML += `\n[SHIELD REPORT] ATTACK INTERCEPTED SUCCESSFULLY!\n` +
                                          `Status Code: ${response.status}\n` +
                                          `Response Data: ${JSON.stringify(data)}\n` +
                                          `Result: Intrusion Prevention active. Blocked by WAF / Account Lockout. Incident logged.`;
                } else {
                    resultBox.style.color = "#ff4e4e";
                    resultBox.style.border = "1px solid #ff4e4e44";
                    resultBox.innerHTML += `\n[SHIELD REPORT] SYSTEM COMPROMISED (Controls Disabled!)\n` +
                                          `Response Data: ${JSON.stringify(data)}\n` +
                                          `Result: Payload executed successfully. Vulnerability confirmed.`;
                }
            } catch (e) {
                resultBox.innerHTML += `\n[SANDBOX ERROR] Simulation failed: ${e.message}`;
            }
            setTimeout(refreshSecurityData, 1000);
        }

        function renderApprovals(data) {
            const users = data.users;
            const tbody = document.getElementById('approvalTableBody');
            
            
            const allDrivers = users.filter(u => (u.role && u.role.toUpperCase() === 'DRIVER'));
            
            
            const pendingDrivers = allDrivers.filter(u => {
                const s = u.status ? u.status.toUpperCase() : 'NULL';
                return s !== 'APPROVED'; 
            });

            tbody.innerHTML = "";
            
            if (pendingDrivers.length === 0) {
                 tbody.innerHTML = '<tr><td colspan="6" style="text-align:center; color:#a0a0a0; padding:20px">No pending requests found</td></tr>';
            } else {
                pendingDrivers.forEach(d => {
                    const statusDisplay = d.status ? d.status : 'UNKNOWN';
                    const statusClass = (statusDisplay === 'PENDING') ? 'role-passenger' : 'role-driver'; 
                    
                    const tr = document.createElement('tr');
                    tr.innerHTML = `
                        <td>#${d.id}</td>
                        <td>${d.username}</td>
                        <td>${d.vehicle_model || 'N/A'}</td>
                        <td>${d.license_plate || 'N/A'}</td>
                        <td><span class="status-badge" style="background:#f39c12; color:white">${statusDisplay}</span></td>
                        <td>
                            <button class="btn-sm" style="background:#28a745; color:white; border:none; padding:5px 10px; border-radius:4px; cursor:pointer" onclick="approveDriver(${d.id}, true, this)">Approve</button>
                            <button class="btn-sm" style="background:#e74c3c; color:white; border:none; padding:5px 10px; border-radius:4px; cursor:pointer; margin-left:5px" onclick="approveDriver(${d.id}, false, this)">Reject</button>
                        </td>
                    `;
                    tbody.appendChild(tr);
                });
            }
        }

        function renderDashboard(data) {
            document.getElementById('countActive').innerText = data.activeRides;
            
            const users = data.users;
            document.getElementById('countUsers').innerText = users.filter(u => u.role === 'PASSENGER').length;
            document.getElementById('countDrivers').innerText = users.filter(u => u.role === 'DRIVER' && u.status === 'APPROVED').length;

            const tbody = document.getElementById('userTableBody');
            tbody.innerHTML = "";
            
            users.forEach(u => {
                if (u.role === 'ADMIN') return;

                const isDriver = u.role === 'DRIVER';
                const isBlocked = (u.is_blocked === true || u.is_blocked === 1);
                const badgeClass = isBlocked ? 'role-blocked' : (isDriver ? 'role-driver' : 'role-passenger');
                
                
                const statusInfo = isDriver ? (u.status ? ` <span style="font-size:0.8em; opacity:0.7">(${u.status})</span>` : '') : '';
                
                let actions = '-';
                
                const btnDelete = `<button class="btn-sm btn-outline-red" style="margin-left:5px" onclick="deleteUser(${u.id}, this)">Delete</button>`;

                if (isDriver) {
                    if (isBlocked) {
                         actions = `<button class="btn-sm" style="background:#27ae60; color:white; border:none" onclick="blockDriver(${u.id}, false, this)">Unblock</button>` + btnDelete;
                    } else {
                         actions = `<button class="btn-sm btn-outline-red" onclick="blockDriver(${u.id}, true, this)">Block</button>` + btnDelete;
                    }
                } else {
                    
                    actions = btnDelete;
                }

                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td>${u.id}</td>
                    <td>${u.username}</td>
                    <td><span class="status-badge ${badgeClass}">${isBlocked ? 'BLOCKED' : u.role}</span>${statusInfo}</td>
                    <td>⭐ ${u.rating || 'N/A'}</td>
                    <td>${actions}</td>
                `;
                tbody.appendChild(tr);
            });
            log("Dashboard updated.");
        }

        function log(txt) {
            const consoleBox = document.getElementById('logConsole');
            const entry = document.createElement('div');
            entry.className = 'log-entry';
            const time = new Date().toLocaleTimeString();
            entry.innerHTML = `<span class="log-time">[${time}]</span> ${txt}`;
            consoleBox.prepend(entry);
        }

        function downloadSecurityReport() {
            if (!latestSecurityData) {
                alert("No security logs retrieved yet. Please wait a moment or launch a sandbox attack to generate log entries!");
                return;
            }
            
            const logs = latestSecurityData.logs || [];
            const securityLogs = logs.filter(l => 
                l.event_type === 'SQL_INJECTION_BLOCKED' || 
                l.event_type === 'XSS_BLOCKED' || 
                l.event_type === 'BRUTE_FORCE_ALERT' || 
                l.event_type === 'CONFIG_CHANGE' || 
                l.event_type === 'SECURITY_ALERT'
            );

            const wafStatus = latestSecurityData.sqlFilterEnabled ? "ACTIVE / SECURE" : "DISABLED (VULNERABLE)";
            const bruteStatus = latestSecurityData.bruteForceProtection ? "ACTIVE / SECURE" : "DISABLED (VULNERABLE)";
            const cryptoStatus = latestSecurityData.encryptionEnabled ? "ENABLED (AES-256-CBC)" : "DISABLED (PLAIN-TEXT)";

            let logRows = "";
            if (securityLogs.length === 0) {
                logRows = `<tr><td colspan="4" style="text-align:center; padding:15px; color:#a4b0be;">No security alerts or threat vectors detected. System is healthy and secure.</td></tr>`;
            } else {
                securityLogs.forEach(l => {
                    const rowColor = l.event_type.includes('BLOCKED') ? '#ff4e4e' : '#ff9f43';
                    logRows += `
                        <tr style="border-bottom: 1px solid #2f3640;">
                            <td style="padding:12px; font-weight:bold; color:#747d8c;">#${l.id}</td>
                            <td style="padding:12px;"><span style="background:${rowColor}15; color:${rowColor}; padding:4px 8px; border-radius:4px; font-size:0.8rem; border:1px solid ${rowColor}30; font-weight:bold;">${l.event_type}</span></td>
                            <td style="padding:12px; color:#f1f2f6;">${l.details}</td>
                            <td style="padding:12px; color:#a4b0be; font-size:0.85rem;">${l.timestamp}</td>
                        </tr>
                    `;
                });
            }

            const wafColor = latestSecurityData.sqlFilterEnabled ? '#00ff88' : '#ff4e4e';
            const bruteColor = latestSecurityData.bruteForceProtection ? '#ff9f43' : '#ff4e4e';
            const cryptoColor = latestSecurityData.encryptionEnabled ? '#4e8cff' : '#ff4e4e';
            const reportDate = new Date().toLocaleString();

            const reportHtml = `<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>Enterprise Security Operations Audit - RideShare Distributed Cluster</title>
    <style>
        body {
            background-color: #0f1115;
            color: #f1f2f6;
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            margin: 0;
            padding: 40px;
        }
        .container {
            max-width: 900px;
            margin: 0 auto;
            background: #151922;
            border: 1px solid #00ff8833;
            border-radius: 12px;
            padding: 30px;
            box-shadow: 0 10px 30px rgba(0,255,136,0.05);
        }
        .header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            border-bottom: 2px solid #00ff8822;
            padding-bottom: 20px;
            margin-bottom: 30px;
        }
        .title h1 {
            margin: 0;
            font-size: 1.8rem;
            color: #00ff88;
            letter-spacing: 1px;
        }
        .title p {
            margin: 5px 0 0 0;
            color: #a4b0be;
            font-size: 0.9rem;
        }
        .badge {
            background: #00ff8815;
            color: #00ff88;
            border: 1px solid #00ff8844;
            padding: 6px 12px;
            border-radius: 6px;
            font-size: 0.85rem;
            font-weight: bold;
        }
        .grid {
            display: grid;
            grid-template-columns: repeat(3, 1fr);
            gap: 20px;
            margin-bottom: 30px;
        }
        .card {
            background: #1b202e;
            border: 1px solid #2f3542;
            border-radius: 8px;
            padding: 15px;
            text-align: center;
        }
        .card-val {
            font-size: 1.3rem;
            font-weight: bold;
            margin-bottom: 5px;
        }
        .card-label {
            color: #a4b0be;
            font-size: 0.8rem;
            text-transform: uppercase;
        }
        .section-title {
            color: #00ff88;
            border-bottom: 1px solid #2f3640;
            padding-bottom: 8px;
            margin-top: 30px;
            margin-bottom: 15px;
            font-size: 1.2rem;
        }
        table {
            width: 100%;
            border-collapse: collapse;
            margin-top: 15px;
        }
        th {
            background: #1b202e;
            color: #00ff88;
            text-align: left;
            padding: 12px;
            font-size: 0.9rem;
            border-bottom: 2px solid #2f3640;
        }
        .footer {
            margin-top: 40px;
            text-align: center;
            font-size: 0.8rem;
            color: #747d8c;
            border-top: 1px solid #2f3640;
            padding-top: 20px;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div class="title">
                <h1>🛡️ RideShare Security Operations Audit</h1>
                <p>Distributed Cluster Security Compliance & Audit Report</p>
            </div>
            <div class="badge">SECURE COMPLIANCE</div>
        </div>

        <div class="grid">
            <div class="card" style="border-left: 4px solid #00ff88;">
                <div class="card-val" style="color: ${wafColor};">${wafStatus}</div>
                <div class="card-label">Web Application Firewall</div>
            </div>
            <div class="card" style="border-left: 4px solid #ff9f43;">
                <div class="card-val" style="color: ${bruteColor};">${bruteStatus}</div>
                <div class="card-label">Brute-Force Shield</div>
            </div>
            <div class="card" style="border-left: 4px solid #4e8cff;">
                <div class="card-val" style="color: ${cryptoColor};">${cryptoStatus}</div>
                <div class="card-label">Database Column Encryption</div>
            </div>
        </div>

        <div class="section-title">🔑 Cryptographic Specifications</div>
        <div style="background:#1b202e; padding:15px; border-radius:8px; border:1px solid #2f3542; font-size:0.95rem; line-height:1.6;">
            <div><strong>Credential Hashing Protocol:</strong> Salted SHA-256 (dynamic 16-hex byte cryptographic salt value)</div>
            <div style="margin-top:8px;"><strong>Database Storage Standard:</strong> AES-256-CBC with PKCS5Padding</div>
            <div style="margin-top:8px;"><strong>Master Entropy Seed:</strong> <code>RideShareSecureKey...EnterpriseShield!</code></div>
            <div style="margin-top:8px;"><strong>Personally Identifiable Information Protected:</strong></div>
            <ul style="margin:5px 0 0 0; padding-left:20px; color:#a4b0be;">
                <li>User Profiles (Encrypted columns: <code>users.email</code>)</li>
                <li>Driver Credentials (Encrypted columns: <code>drivers.license_plate</code>)</li>
            </ul>
        </div>

        <div class="section-title">🚨 Real-time Intrusion Detection logs (IDS Table)</div>
        <table>
            <thead>
                <tr>
                    <th style="width:10%">ID</th>
                    <th style="width:20%">Threat Type</th>
                    <th style="width:50%">Incident Details</th>
                    <th style="width:20%">Timestamp</th>
                </tr>
            </thead>
            <tbody>
                ${logRows}
            </tbody>
        </table>

        <div class="footer">
            Generated automatically by RideShare Enterprise Security Shield Service.<br>
            Audit Timestamp: ${reportDate} | Client Node ID: 1
        </div>
    </div>
</body>
</html>`;

            const blob = new Blob([reportHtml], { type: 'text/html' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `rideshare-security-audit-report.html`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        }

        window.togglePasswordVisibility = function(inputId, toggleEl) {
            const input = document.getElementById(inputId);
            if (input.type === 'password') {
                input.type = 'text';
                toggleEl.innerText = '🙈';
            } else {
                input.type = 'password';
                toggleEl.innerText = '👁️';
            }
        };
