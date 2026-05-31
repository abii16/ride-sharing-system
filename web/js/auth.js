let currentRole = 'PASSENGER';
let currentMode = 'LOGIN';

// Parse query params on load to automatically pre-select
window.addEventListener('DOMContentLoaded', () => {
    const params = new URLSearchParams(window.location.search);
    const roleParam = params.get('role');
    
    if (roleParam) {
        const r = roleParam.toUpperCase();
        if (r === 'DRIVER') switchRole('DRIVER');
        else if (r === 'ADMIN') switchRole('ADMIN');
        else switchRole('PASSENGER');
    } else {
        switchRole('PASSENGER');
    }
});

function switchRole(role) {
    currentRole = role;
    
    // Update active tab buttons
    document.querySelectorAll('.role-tab').forEach(tab => {
        if (tab.dataset.role === role) tab.classList.add('active');
        else tab.classList.remove('active');
    });

    // Update body class for custom color transitions
    document.body.className = '';
    if (role === 'PASSENGER') {
        document.body.classList.add('active-passenger');
        document.getElementById('logoDot').style.backgroundColor = 'var(--primary)';
        document.getElementById('portalHeading').innerText = "Passenger Access";
        document.getElementById('passLabel').innerText = "Password";
        document.getElementById('password').placeholder = "Enter your password";
        document.getElementById('modeContainer').style.display = 'flex';
        switchMode(currentMode);
    } else if (role === 'DRIVER') {
        document.body.classList.add('active-driver');
        document.getElementById('logoDot').style.backgroundColor = 'var(--secondary)';
        document.getElementById('portalHeading').innerText = "Driver Portal";
        document.getElementById('passLabel').innerText = "Password";
        document.getElementById('password').placeholder = "Enter your password";
        document.getElementById('modeContainer').style.display = 'flex';
        switchMode(currentMode);
    } else if (role === 'ADMIN') {
        document.body.classList.add('active-admin');
        document.getElementById('logoDot').style.backgroundColor = 'var(--admin)';
        document.getElementById('portalHeading').innerText = "Admin Console";
        document.getElementById('passLabel').innerText = "Secure Key";
        document.getElementById('password').placeholder = "Enter authorization key";
        document.getElementById('modeContainer').style.display = 'none'; // Admin has no register
        switchMode('LOGIN');
    }

    // Set submit button attribute
    const btn = document.getElementById('btnSubmit');
    btn.dataset.role = role;
    btn.className = 'btn-submit';
    
    // Clear alerts
    hideAlert();
}

function switchMode(mode) {
    currentMode = mode;
    
    const modeLogin = document.getElementById('modeLogin');
    const modeSignup = document.getElementById('modeSignup');
    const emailGroup = document.getElementById('emailGroup');
    const driverFields = document.getElementById('driverFields');
    const btnSubmit = document.getElementById('btnSubmit');
    const subText = document.getElementById('portalSubheading');

    if (mode === 'LOGIN') {
        modeLogin.classList.add('active');
        modeSignup.classList.remove('active');
        emailGroup.style.display = 'none';
        driverFields.classList.remove('visible');
        btnSubmit.innerText = 'LOG IN';
        
        if (currentRole === 'PASSENGER') subText.innerText = "Welcome back! Please enter your credentials to log in.";
        else if (currentRole === 'DRIVER') subText.innerText = "Access your driving console and locate ride dispatches.";
        else subText.innerText = "Secure administrative terminal. Unauthorized access is monitored.";
        
    } else {
        modeLogin.classList.remove('active');
        modeSignup.classList.add('active');
        btnSubmit.innerText = 'SIGN UP';
        
        if (currentRole === 'PASSENGER') {
            emailGroup.style.display = 'none';
            driverFields.classList.remove('visible');
            subText.innerText = "Create an account to start booking quick rides instantly.";
        } else if (currentRole === 'DRIVER') {
            emailGroup.style.display = 'flex';
            driverFields.classList.add('visible');
            subText.innerText = "Submit your application to join our premium fleet of drivers.";
        }
    }
    
    hideAlert();
}

async function handleSubmit() {
    const user = document.getElementById('username').value.trim();
    const pass = document.getElementById('password').value;
    const btn = document.getElementById('btnSubmit');
    
    if (!user || !pass) {
        showAlert("Please fill out all required fields.", "error");
        return;
    }

    btn.disabled = true;
    btn.innerText = "PROCESSING...";
    hideAlert();

    try {
        if (currentMode === 'LOGIN') {
            // LOGIN FLOW
            const res = await fetch('/api/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username: user, password: pass })
            });
            const data = await res.json();
            
            if (data.success) {
                // Check authorization role alignment
                if (currentRole === 'ADMIN' && data.role !== 'ADMIN') {
                    throw new Error("Access Denied: Account is not an administrator.");
                }
                if (currentRole === 'DRIVER' && data.role !== 'DRIVER') {
                    throw new Error("Access Denied: Account is not a registered driver.");
                }
                if (currentRole === 'PASSENGER' && data.role !== 'PASSENGER') {
                    throw new Error("Access Denied: Account is not a passenger.");
                }

                showAlert("Authentication successful! Redirecting...", "success");
                
                // Store sessions specifically matching existing formats
                const timestamp = Date.now();
                if (currentRole === 'PASSENGER') {
                    localStorage.setItem('passenger_sessionId', data.sessionId);
                    localStorage.setItem('passenger_userId', data.userId);
                    localStorage.setItem('passenger_username', user);
                    localStorage.setItem('passenger_lastActivity', timestamp);
                    setTimeout(() => window.location.href = 'passenger.html', 1000);
                } else if (currentRole === 'DRIVER') {
                    localStorage.setItem('driver_sessionId', data.sessionId);
                    localStorage.setItem('driver_username', user);
                    localStorage.setItem('driver_email', data.email || 'No Email');
                    localStorage.setItem('driver_model', data.vehicle_model || 'N/A');
                    localStorage.setItem('driver_plate', data.license_plate || 'N/A');
                    localStorage.setItem('driver_rating', data.rating || '5.0');
                    localStorage.setItem('driver_uid', data.userId);
                    localStorage.setItem('driver_lastActivity', timestamp);
                    setTimeout(() => window.location.href = 'driver.html', 1000);
                } else if (currentRole === 'ADMIN') {
                    localStorage.setItem('admin_sessionId', data.sessionId);
                    localStorage.setItem('admin_lastActivity', timestamp);
                    setTimeout(() => window.location.href = 'admin.html', 1000);
                }
            } else {
                throw new Error(data.message || "Invalid username or password credentials.");
            }
        } else {
            // REGISTRATION FLOW
            let payload = {
                username: user,
                password: pass,
                role: currentRole
            };

            if (currentRole === 'DRIVER') {
                const email = document.getElementById('email').value.trim();
                const model = document.getElementById('model').value.trim();
                const plate = document.getElementById('plate').value.trim();
                
                if (!email || !model || !plate) {
                    throw new Error("Please fill out email, vehicle model, and license plate.");
                }
                
                payload.email = email;
                payload.vehicle_model = model;
                payload.license_plate = plate;
            }

            const res = await fetch('/api/register', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await res.json();
            
            if (data.success) {
                if (currentRole === 'DRIVER') {
                    showAlert("Application received! PENDING administrator approval.", "success");
                    setTimeout(() => {
                        switchMode('LOGIN');
                        document.getElementById('password').value = "";
                    }, 3000);
                } else {
                    showAlert("Registration successful! You can now log in.", "success");
                    setTimeout(() => {
                        switchMode('LOGIN');
                        document.getElementById('password').value = "";
                    }, 1500);
                }
            } else {
                throw new Error(data.message || "Registration failed. Try a different username.");
            }
        }
    } catch (e) {
        showAlert(e.message, "error");
    } finally {
        btn.disabled = false;
        btn.innerText = currentMode;
    }
}

function showAlert(text, type) {
    const box = document.getElementById('alertBox');
    box.innerText = text;
    box.className = 'alert-box ' + (type === 'success' ? 'alert-success' : 'alert-error');
    box.style.display = 'block';
}

function hideAlert() {
    document.getElementById('alertBox').style.display = 'none';
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
