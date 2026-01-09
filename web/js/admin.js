        let sessionId = null;

        function logout() {
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
                    document.getElementById('authScreen').style.display = 'none';
                    document.getElementById('app').style.display = 'flex';
                    log("Admin Session Established.");
                    refreshData();
                    startPolling();
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
            
            
            document.getElementById(viewId).style.display = 'block';

            
            if(viewId === 'view-approvals') refreshDriverRequests();
            if(viewId === 'view-users' || viewId === 'view-dashboard') refreshData();
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
                            } else {
                                log("Event: " + JSON.stringify(msg));
                            }
                        });
                    }
                } catch(e) {}
            }, 1000);
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
            document.getElementById('countDrivers').innerText = users.filter(u => u.role === 'DRIVER').length;

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
