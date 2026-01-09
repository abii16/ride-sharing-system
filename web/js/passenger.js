        let sessionId = null;
        let userId = null;
        let isRegisterMode = false;
        let selectedRideType = 'STANDARD';
        let map;
        let pickupPlace = null;
        let destPlace = null;
        let pickupMarker = null;
        let destMarker = null;
        let routeLine = null;
        let lastRideStatus = "NONE"; 
        let currentDriverId = -1;
        let starRating = 5;
        
        // --- FAILOVER LOGIC ---
        let currentApiBase = ''; // Current host (relative by default)
        
        async function fetchWithFailover(url, options) {
            try {
                // If url starts with /, prepend current base.
                const fullUrl = url.startsWith('/') ? (currentApiBase + url) : url;
                const resp = await fetch(fullUrl, options);
                return resp;
            } catch (e) {
                console.error("Connection Failed:", e);
                const newHost = prompt("CONNECTION LOST! The server (" + (currentApiBase||'localhost') + ") is unreachable.\n\nPlease enter the IP address of a BACKUP SERVER (e.g., 192.168.1.50):", "192.168.1.");
                if (newHost) {
                    currentApiBase = "http://" + newHost + ":8080";
                    alert("Switched to backup node: " + currentApiBase);
                    // Retry once
                    const retryUrl = url.startsWith('/') ? (currentApiBase + url) : url;
                    return await fetch(retryUrl, options);
                }
                throw e;
            }
        }
        // ----------------------


        
        const defaultCenter = { lat: 9.005401, lng: 38.763611 };

        
        function logout() {
            location.reload();
        }

        function toggleAuth() {
            isRegisterMode = !isRegisterMode;
            document.getElementById('authTitle').innerText = isRegisterMode ? "Create Account" : "Welcome Back";
            document.getElementById('authBtn').innerText = isRegisterMode ? "Sign Up" : "Log In";
            document.getElementById('toggleLink').innerText = isRegisterMode ? "Already calculate account? Log In" : "Create an account";
        }

        async function handleAuth() {
            console.log("Handle Auth Clicked");
            const btn = document.getElementById('authBtn');
            btn.disabled = true;
            btn.innerText = "Processing...";
            try {
                if(isRegisterMode) await register(); else await login();
            } catch (e) {
                alert("Auth Error: " + e.message);
            } finally {
                btn.disabled = false;
                btn.innerText = isRegisterMode ? "Sign Up" : "Log In";
            }
        }

        async function login() {
            const user = document.getElementById('username').value;
            const pass = document.getElementById('password').value;
            console.log("Logging in as " + user);
            try {
                const res = await fetchWithFailover('/api/login', { 
                    method: 'POST', 
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ username: user, password: pass }) 
                });
                if (!res.ok) throw new Error("Server returned " + res.status);
                
                const data = await res.json();
                console.log("Login res:", data);
                
                if(data.success) {
                    sessionId = data.sessionId;
                    userId = data.userId;
                    document.getElementById('loginConfig').style.display = 'none';
                    document.getElementById('appInterface').classList.remove('hidden');
                    document.getElementById('userDisplay').innerText = user;
                    startPolling();
                } else {
                    alert(data.message || data.error || "Login Failed");
                }
            } catch(e) { 
                console.error(e);
                alert("Login Error: " + e.message); 
            }
        }

        async function register() {
            
             const user = document.getElementById('username').value;
            const pass = document.getElementById('password').value;
             try {
                const res = await fetch('/api/register', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username: user, password: pass, role: 'PASSENGER' })
                });
                const data = await res.json();
                if(data.success) { alert("Registered!"); toggleAuth(); }
                else alert(data.message);
            } catch(e){}
        }

        
        function selectRide(type, el) {
            selectedRideType = type;
            document.querySelectorAll('.ride-card').forEach(c => c.classList.remove('selected'));
            el.classList.add('selected');
            document.getElementById('reqBtn').innerText = "Request " + type;
        }

        
        function initMap() {
            
            if (typeof L === 'undefined') return;

            map = L.map('map', {
                maxBounds: [[3.4, 33.0], [14.9, 48.0]],
                maxBoundsViscosity: 1.0,
                minZoom: 6
            }).setView([9.005401, 38.763611], 13); 

            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
            }).addTo(map);
        }

        
        async function manualLocate(type) {
            const inputId = type === 'pickup' ? 'pickup' : 'destination';
            const value = document.getElementById(inputId).value;
            
            if(!value) return;

            try {
                const response = await fetch(`https://nominatim.openstreetmap.org/search?format=json&countrycodes=et&q=${encodeURIComponent(value)}`);
                const data = await response.json();

                if (data.length > 0) {
                    const place = data[0];
                    const lat = parseFloat(place.lat);
                    const lon = parseFloat(place.lon);
                    
                    if (type === 'pickup') {
                        if (pickupMarker) map.removeLayer(pickupMarker);
                        pickupMarker = L.marker([lat, lon]).addTo(map).bindPopup("Pickup: " + value).openPopup();
                        pickupPlace = { geometry: { location: { lat: () => lat, lng: () => lon } } };
                    } else {
                        if (destMarker) map.removeLayer(destMarker);
                        destMarker = L.marker([lat, lon]).addTo(map).bindPopup("Destination: " + value).openPopup();
                        destPlace = { geometry: { location: { lat: () => lat, lng: () => lon } } };
                    }
                    
                    checkAndRoute();
                } else {
                    alert('Location not found');
                }
            } catch (e) {
                alert('Geocode failed: ' + e.message);
            }
        }

        async function checkAndRoute() {
            if (pickupPlace && destPlace) {
                const pLat = pickupPlace.geometry.location.lat();
                const pLon = pickupPlace.geometry.location.lng();
                const dLat = destPlace.geometry.location.lat();
                const dLon = destPlace.geometry.location.lng();

                
                try {
                    const url = `https://router.project-osrm.org/route/v1/driving/${pLon},${pLat};${dLon},${dLat}?overview=full&geometries=geojson`;
                    const response = await fetch(url);
                    const data = await response.json();

                    if (data.code === 'Ok') {
                        const route = data.routes[0];
                        const geometry = route.geometry;

                        
                        const distKm = (route.distance / 1000).toFixed(1);
                        const durationMin = Math.round(route.duration / 60);

                        
                        const price = Math.round(distKm * 100);

                        if(document.getElementById('priceRide')) {
                            document.getElementById('priceRide').innerText = 'ETB ' + price;
                            document.getElementById('distRide').innerText = distKm + ' km';
                            document.getElementById('timeRide').innerText = durationMin + ' min';
                        }

                        if (routeLine) map.removeLayer(routeLine);
                        
                        
                        routeLine = L.geoJSON(geometry, {
                            style: { color: 'blue', weight: 5, opacity: 0.7 }
                        }).addTo(map);

                        map.fitBounds(routeLine.getBounds(), { padding: [50, 50] });
                    }
                } catch (e) {
                    console.log("Routing failed, falling back to line", e);
                    
                    if (routeLine) map.removeLayer(routeLine);
                    routeLine = L.polyline([[pLat, pLon], [dLat, dLon]], {color: 'blue', dashArray: '5, 10'}).addTo(map);
                    map.fitBounds(routeLine.getBounds());
                }
                
            } else if (pickupPlace) {
                map.setView([pickupPlace.geometry.location.lat(), pickupPlace.geometry.location.lng()], 15);
            } else if (destPlace) {
                map.setView([destPlace.geometry.location.lat(), destPlace.geometry.location.lng()], 15);
            }
        }
        
        
        function setupAutocomplete(id, callback) {
           
        }

        
        

        
        async function requestRide() {
            if (!userId) { alert("Please login."); return; }
            
            
            const srcLat = pickupPlace ? pickupPlace.geometry.location.lat() : 9.0109;
            const srcLon = pickupPlace ? pickupPlace.geometry.location.lng() : 38.7612;
            const destLat = destPlace ? destPlace.geometry.location.lat() : 8.9771;
            const destLon = destPlace ? destPlace.geometry.location.lng() : 38.7993;

            document.getElementById('reqBtn').disabled = true;
            document.getElementById('statusBox').style.display = 'flex';
            document.getElementById('statusText').innerText = "Connecting to nearby drivers...";
            lastRideStatus = "REQUESTED";
            
            try {
                await fetch('/api/send', {
                    method: 'POST',
                    headers: { 'X-Session-ID': sessionId },
                    body: JSON.stringify({ 
                        type: 'RIDE_REQUEST',
                        passengerId: parseInt(userId),
                        srcLat: srcLat, srcLon: srcLon,
                        destLat: destLat, destLon: destLon,
                        rideType: selectedRideType
                    })
                });
            } catch(e) {
                alert("Network error: " + e.message);
                document.getElementById('reqBtn').disabled = false;
            }
        }

        
        function setRate(n) {
            starRating = n;
            const stars = document.getElementById('starContainer').children;
            for(let i=0; i<5; i++) {
                stars[i].style.color = (i < n) ? '#f39c12' : '#555';
            }
        }

        async function submitRating() {
            try {
                await fetch('/api/send', {
                    method: 'POST',
                    headers: { 'X-Session-ID': sessionId },
                    body: JSON.stringify({ 
                        type: 'RATE_DRIVER', 
                        driverId: currentDriverId, 
                        rating: starRating 
                    })
                });
                document.getElementById('ratingModal').classList.add('hidden');
                
                lastRideStatus = "NONE";
                document.getElementById('statusBox').style.display = 'none';
                document.getElementById('driverPanel').style.display = 'none';
                document.getElementById('reqBtn').disabled = false;
                document.getElementById('reqBtn').innerText = "Request " + selectedRideType;
            } catch(e) {}
        }

        function startPolling() {
            setInterval(async () => {
                
                if(sessionId && userId && lastRideStatus !== 'COMPLETED' && lastRideStatus !== 'NONE') { 
                     fetch('/api/send', {
                        method: 'POST',
                        headers: { 'X-Session-ID': sessionId },
                        body: JSON.stringify({ type: 'CHECK_RIDE_STATUS', userId: parseInt(userId) })
                    }).catch(()=>{}); 
                }

                try {
                    const res = await fetch('/api/updates', { headers: { 'X-Session-ID': sessionId } });
                    const msgs = await res.json();
                    
                    if (msgs.length > 0) {
                        const lastMsg = msgs[msgs.length - 1]; 
                        if (lastMsg.type === 'RIDE_UPDATE') {
                            const status = lastMsg.status;
                            
                            
                            if (status === lastRideStatus) return;
                            lastRideStatus = status;

                            const box = document.getElementById('statusBox');
                            const txt = document.getElementById('statusText');
                            
                            if (status === 'ASSIGNED') {
                                box.style.background = '#d4edda';
                                box.style.borderLeftColor = '#28a745';
                                box.style.color = '#155724';
                                document.querySelector('.spinner').style.display = 'none';
                                txt.innerText = "Driver is on the way!";
                                
                                document.getElementById('driverPanel').style.display = 'block';
                                
                                const dName = lastMsg.driverName || ("Driver #" + lastMsg.driverId);
                                const dCar = lastMsg.driverCar || "Standard Vehicle";
                                const dPlate = lastMsg.driverPlate || "---";

                                document.getElementById('driverName').innerText = dName;
                                if(document.getElementById('driverCar')) document.getElementById('driverCar').innerText = dCar;
                                if(document.getElementById('driverPlate')) document.getElementById('driverPlate').innerText = dPlate;
                                
                                currentDriverId = lastMsg.driverId;
                                
                            } else if (status === 'COMPLETED') {
                                document.getElementById('ratingModal').classList.remove('hidden');
                                txt.innerText = "Ride Completed.";
                                box.style.background = '#cce5ff';
                            
                            } else if (status === 'NO_DRIVERS_FOUND') {
                                box.style.background = '#fff3cd';
                                box.style.borderLeftColor = '#ffc107';
                                box.style.color = '#856404';
                                document.querySelector('.spinner').style.display = 'none';
                                txt.innerText = "No driver online";
                                
                                const reqBtn = document.getElementById('reqBtn');
                                reqBtn.disabled = false;
                                reqBtn.innerText = "No Driver Online";
                                setTimeout(() => { reqBtn.innerText = "Request " + selectedRideType; }, 2000);
                            }
                        }
                    }
                } catch(e) {}
            }, 1000);
        }
        
        document.addEventListener('DOMContentLoaded', initMap);
