        let sessionId = localStorage.getItem('driver_sessionId');

        const INACTIVITY_TIMEOUT = 10 * 60 * 1000; // 10 minutes in milliseconds
        let inactivityInterval = null;

        function resetActivityTimer() {
            localStorage.setItem('driver_lastActivity', Date.now());
        }

        function checkInactivity() {
            if (!sessionId) return;
            const last = parseInt(localStorage.getItem('driver_lastActivity') || Date.now());
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
                const last = parseInt(localStorage.getItem('driver_lastActivity') || Date.now());
                if (Date.now() - last > INACTIVITY_TIMEOUT) {
                    localStorage.removeItem('driver_sessionId');
                    localStorage.removeItem('driver_username');
                    localStorage.removeItem('driver_email');
                    localStorage.removeItem('driver_model');
                    localStorage.removeItem('driver_plate');
                    localStorage.removeItem('driver_rating');
                    localStorage.removeItem('driver_uid');
                    sessionId = null;
                    window.location.href = 'auth.html?role=DRIVER';
                    return;
                }
                
                const user = localStorage.getItem('driver_username');
                const email = localStorage.getItem('driver_email');
                const model = localStorage.getItem('driver_model');
                const plate = localStorage.getItem('driver_plate');
                const rating = localStorage.getItem('driver_rating');
                const uid = localStorage.getItem('driver_uid');

                document.getElementById('loginModal').style.display = 'none';
                document.getElementById('dashboard').style.display = 'grid';
                document.getElementById('navUser').style.display = 'flex';
                document.getElementById('dashName').innerText = user || 'Driver';
                document.getElementById('dashEmail').innerText = email || 'No Email';
                document.getElementById('dashModel').innerText = model || 'N/A';
                document.getElementById('dashPlate').innerText = plate || 'N/A';
                document.getElementById('dashRating').innerText = '★ ' + (rating || '5.0');
                document.getElementById('dashName').dataset.uid = uid;
                
                document.getElementById('onlineToggle').checked = true;
                toggleOnline();
                
                setTimeout(initMap, 100);
                startGPS();
                startPolling();
                setupInactivityMonitor();
            } else {
                window.location.href = 'auth.html?role=DRIVER';
            }
        });
        let map;
        let driverMarker;
        
        
        function showRegister() {
            document.getElementById('loginForm').style.display = 'none';
            document.getElementById('registerForm').style.display = 'block';
            document.getElementById('modalTitle').innerText = 'New Driver Application';
            document.getElementById('msgBox').innerText = '';
        }

        function showLogin() {
            document.getElementById('registerForm').style.display = 'none';
            document.getElementById('loginForm').style.display = 'block';
            document.getElementById('modalTitle').innerText = 'Driver Portal';
            document.getElementById('msgBox').innerText = '';
        }

        function logout() {
            localStorage.removeItem('driver_sessionId');
            localStorage.removeItem('driver_username');
            localStorage.removeItem('driver_email');
            localStorage.removeItem('driver_model');
            localStorage.removeItem('driver_plate');
            localStorage.removeItem('driver_rating');
            localStorage.removeItem('driver_uid');
            localStorage.removeItem('driver_lastActivity');
            location.reload();
        }

        
        async function login() {
            const user = document.getElementById('username').value;
            const pass = document.getElementById('password').value;
             try {
                const res = await fetch('/api/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username: user, password: pass })
                });
                const data = await res.json();
                
                if (data.success) {
                    
                    if(data.role !== 'DRIVER') {
                        document.getElementById('msgBox').innerText = "Login Failed";
                        return;
                    }

                    sessionId = data.sessionId; 
                    localStorage.setItem('driver_sessionId', sessionId);
                    localStorage.setItem('driver_username', user);
                    localStorage.setItem('driver_email', data.email || 'No Email');
                    localStorage.setItem('driver_model', data.vehicle_model || 'N/A');
                    localStorage.setItem('driver_plate', data.license_plate || 'N/A');
                    localStorage.setItem('driver_rating', data.rating || '5.0');
                    localStorage.setItem('driver_uid', data.userId);
                    resetActivityTimer();

                    document.getElementById('loginModal').style.display = 'none';
                    document.getElementById('dashboard').style.display = 'grid';
                    document.getElementById('navUser').style.display = 'flex';
                    document.getElementById('dashName').innerText = user;
                    document.getElementById('dashEmail').innerText = data.email || 'No Email';
                    document.getElementById('dashModel').innerText = data.vehicle_model || 'N/A';
                    document.getElementById('dashPlate').innerText = data.license_plate || 'N/A';
                    document.getElementById('dashRating').innerText = '★ ' + (data.rating || '5.0');
                    document.getElementById('dashName').dataset.uid = data.userId;
                    
                    document.getElementById('onlineToggle').checked = true;
                    toggleOnline();
                    
                    setTimeout(initMap, 100);
                    startGPS();
                    startPolling();
                    setupInactivityMonitor();
                } else {
                    document.getElementById('msgBox').innerText = data.message || "Login Failed";
                }
            } catch (e) { document.getElementById('msgBox').innerText = "Error: " + e.message; }
        }

        async function register() {
            const user = document.getElementById('regUser').value;
            const pass = document.getElementById('regPass').value;
            const model = document.getElementById('regModel').value;
            const plate = document.getElementById('regPlate').value;

            if(!user || !pass) {
                document.getElementById('msgBox').innerText = "Username and Password required.";
                return;
            }

            try {
                const res = await fetch('/api/register', { 
                    method: 'POST', 
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ 
                        username: user, 
                        password: pass, 
                        role: 'DRIVER',
                        vehicle_model: model,
                        license_plate: plate
                    }) 
                });
                const data = await res.json();
                
                if(data.success) { 
                    alert("Application Submitted! Status: PENDING.\nPlease wait for admin approval."); 
                    showLogin();
                    document.getElementById('msgBox').innerText = "Application received. Please check back later.";
                    document.getElementById('msgBox').style.color = "orange";
                } else {
                    document.getElementById('msgBox').innerText = data.message;
                    document.getElementById('msgBox').style.color = "red";
                }
            } catch(e) {
                document.getElementById('msgBox').innerText = "Connection Error";
            }
        }

        function toggleOnline() {
            const isOnline = document.getElementById('onlineToggle').checked;
            const box = document.getElementById('statusBox');
            
            
            if (sessionId) {
                fetch('/api/send', {
                    method: 'POST',
                    headers: { 'X-Session-ID': sessionId },
                    body: JSON.stringify({ 
                        type: 'DRIVER_STATUS', 
                        driverId: parseInt(document.getElementById('dashName').dataset.uid),
                        status: isOnline 
                    })
                }).catch(e => console.error("Status Update Error:", e));
            }

            if (isOnline) {
                box.classList.add('online');
                document.getElementById('statusText').innerText = "You are Online";
            } else {
                box.classList.remove('online');
                document.getElementById('statusText').innerText = "You are Offline";
            }
        }

        
        function initMap() {
            
            if (typeof L === 'undefined') {
                setTimeout(initMap, 200);
                return;
            }

            
            if(map) {
                setTimeout(() => map.invalidateSize(), 100);
                return;
            }

            try {
                const startPos = [9.005401, 38.763611];
                map = L.map('map', {
                    maxBounds: [[3.4, 33.0], [14.9, 48.0]],
                    maxBoundsViscosity: 1.0,
                    minZoom: 6
                }).setView(startPos, 14);

                L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
                }).addTo(map);

                driverMarker = L.marker(startPos).addTo(map);

                
                setTimeout(() => { map.invalidateSize(); }, 100);
                setTimeout(() => { map.invalidateSize(); }, 500);
                setTimeout(() => { map.invalidateSize(); }, 1000);
            } catch(e) {
                console.error("Map Init Error:", e);
            }
        }

        function startGPS() {
            let lat = 9.005401;
            let lon = 38.763611;
            
            
            setInterval(() => {
                if(!document.getElementById('onlineToggle').checked) return;

                lat += (Math.random() - 0.5) * 0.0005;
                lon += (Math.random() - 0.5) * 0.0005;
                const pos = [lat, lon];
                
                
                if(driverMarker) driverMarker.setLatLng(pos);
                if(map) map.panTo(pos);
                
                
                fetch('/api/send', {
                    method: 'POST',
                    headers: { 'X-Session-ID': sessionId },
                    body: JSON.stringify({ 
                        type: 'LOCATION_UPDATE', 
                        driverId: parseInt(document.getElementById('dashName').dataset.uid),
                        lat: lat, 
                        lon: lon 
                    })
                }).catch(e => console.error("GPS Error:", e));

                
                fetch(`https://nominatim.openstreetmap.org/reverse?format=json&lat=${lat}&lon=${lon}`)
                    .then(response => response.json())
                    .then(data => {
                        if (data && data.address) {
                            const addr = data.address.road || data.address.suburb || "Addis Ababa";
                            document.getElementById('locationText').innerText = addr;
                        }
                    })
                    .catch(e => console.error("Geocoding Error:", e));

            }, 3000); 
        }

        
        function startPolling() {
            setInterval(async () => {
                try {
                    const res = await fetch('/api/updates', { headers: { 'X-Session-ID': sessionId } });
                    const msgs = await res.json();
                    if (msgs.length > 0) {
                        const lastMsg = msgs[msgs.length - 1];
                        if (lastMsg.type === 'NOTIFY_DRIVER') addRequestToUI(lastMsg);
                    }
                } catch(e) {}
            }, 1000);
        }

        function addRequestToUI(msg) {
            const list = document.getElementById('requestsList');
            if(list.innerText.includes("Waiting")) list.innerHTML = "";
            
            const div = document.createElement('div');
            div.className = 'req-card';
            div.id = 'ride-' + msg.rideId;
            div.innerHTML = `
                <div class="req-info">
                    <div style="font-weight:600; font-size:1.1rem; color:#333">New Trip Request</div>
                    <div style="color:#555; font-size:0.9rem">📍 ${msg.message}</div>
                    <div style="color:var(--success); margin-top:5px; font-weight:600">$150.00 • Economy</div>
                </div>
                <div>
                    <button class="btn-green" onclick="acceptRide(${msg.rideId}, this)">Accept</button>
                    <button style="border:none; background:none; color:#dc3545; cursor:pointer; margin-top:5px; font-size:0.85rem; width:100%" onclick="this.parentElement.parentElement.remove()">Decline</button>
                </div>
            `;
            list.prepend(div);
        }

        function acceptRide(rideId, btn) {
            
            const card = document.getElementById('ride-' + rideId);
            card.style.borderLeftColor = "#007bff";
            card.querySelector('.req-info').innerHTML = `
                <div style="font-weight:600; font-size:1.1rem; color:#007bff">Trip In Progress</div>
                <div style="color:#555; font-size:0.9rem">Driving to destination...</div>
            `;
            
            const actionDiv = card.querySelector('div:last-child');
            actionDiv.innerHTML = `<button class="btn-green" style="background:#007bff" onclick="completeRide(${rideId}, this)">Complete Ride</button>`;
        }

        async function completeRide(rideId, btn) {
            if (!checkConfirm(btn, "Finish?")) return;
            
            if (sessionId) {
                await fetch('/api/send', {
                    method: 'POST',
                    headers: { 'X-Session-ID': sessionId },
                    body: JSON.stringify({ 
                        type: 'RIDE_COMPLETE', 
                        rideId: rideId,
                        driverId: parseInt(document.getElementById('dashName').dataset.uid)
                    })
                });
            }
            
            document.getElementById('ride-' + rideId).remove();
            
            if(document.getElementById('requestsList').children.length === 0) {
                 document.getElementById('requestsList').innerHTML = '<div style="text-align:center; padding: 2rem; color:#999; font-style:italic">Waiting for dispatch...</div>';
            }
        }

        function checkConfirm(btn, text) {
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
