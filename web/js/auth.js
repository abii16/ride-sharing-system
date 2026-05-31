let currentRole = 'PASSENGER'; // Only meaningful during registration
let currentMode = 'LOGIN';

// Parse query params on load to automatically pre-select
window.addEventListener('DOMContentLoaded', () => {
    const params = new URLSearchParams(window.location.search);
    const roleParam = params.get('role');
    
    if (roleParam) {
        const r = roleParam.toUpperCase();
        if (r === 'DRIVER') {
            currentRole = 'DRIVER';
        } else if (r === 'ADMIN') {
            currentRole = 'ADMIN';
        }
    }
    
    switchMode('LOGIN');
});

function switchRole(role) {
    if (currentMode === 'LOGIN') return; // Roles not switched manually during login
    
    currentRole = role;
    
    // Update active tab buttons
    document.querySelectorAll('.role-tab').forEach(tab => {
        if (tab.dataset.role === role) tab.classList.add('active');
        else tab.classList.remove('active');
    });

    // Update body classes & logo dot colors for visual consistency
    document.body.className = '';
    if (role === 'PASSENGER') {
        document.body.classList.add('active-passenger');
        document.getElementById('logoDot').style.backgroundColor = 'var(--primary)';
        document.getElementById('portalHeading').innerText = "Create Account";
        document.getElementById('portalSubheading').innerText = "Create an account to start booking quick rides instantly.";
        document.getElementById('emailGroup').style.display = 'none';
        document.getElementById('driverFields').classList.remove('visible');
    } else if (role === 'DRIVER') {
        document.body.classList.add('active-driver');
        document.getElementById('logoDot').style.backgroundColor = 'var(--secondary)';
        document.getElementById('portalHeading').innerText = "Driver Application";
        document.getElementById('portalSubheading').innerText = "Submit your application to join our premium fleet of drivers.";
        document.getElementById('emailGroup').style.display = 'flex';
        document.getElementById('driverFields').classList.add('visible');
    }

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
    const heading = document.getElementById('portalHeading');
    const roleTabs = document.getElementById('authRoleTabs');

    document.body.className = '';

    if (mode === 'LOGIN') {
        document.body.classList.add('active-passenger', 'active-login');
        document.getElementById('logoDot').style.backgroundColor = 'var(--primary)';
        
        modeLogin.classList.add('active');
        modeSignup.classList.remove('active');
        
        roleTabs.style.display = 'none';
        emailGroup.style.display = 'none';
        driverFields.classList.remove('visible');
        
        heading.innerText = 'Sign In';
        btnSubmit.innerText = 'LOG IN';
        btnSubmit.style.background = 'var(--primary)';
        btnSubmit.style.boxShadow = '0 4px 15px var(--primary-glow)';
        
        // Context-aware subtitle on login
        if (currentRole === 'ADMIN') {
            subText.innerText = "Secure administrative terminal. Enter credentials to log in.";
        } else if (currentRole === 'DRIVER') {
            subText.innerText = "Access your driving console and locate ride dispatches.";
        } else {
            subText.innerText = "Welcome back! Enter your credentials to access the RideShare network.";
        }
        
    } else {
        // SIGN UP MODE
        modeLogin.classList.remove('active');
        modeSignup.classList.add('active');
        
        roleTabs.style.display = 'flex';
        btnSubmit.innerText = 'SIGN UP';
        
        // Default sign up is Passenger unless driver was requested
        if (currentRole === 'DRIVER') {
            switchRole('DRIVER');
            btnSubmit.style.background = 'var(--secondary)';
            btnSubmit.style.boxShadow = '0 4px 15px var(--secondary-glow)';
        } else {
            switchRole('PASSENGER');
            btnSubmit.style.background = 'var(--primary)';
            btnSubmit.style.boxShadow = '0 4px 15px var(--primary-glow)';
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
            // UNIFIED LOGIN - Automatic Role Detection
            const res = await fetch('/api/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username: user, password: pass })
            });
            const data = await res.json();
            
            if (data.success) {
                showAlert(`Authenticated! Logging into ${data.role} portal...`, "success");
                
                // Store sessions matching role returned from the database
                const timestamp = Date.now();
                const role = data.role ? data.role.toUpperCase() : 'PASSENGER';

                if (role === 'ADMIN') {
                    localStorage.setItem('admin_sessionId', data.sessionId);
                    localStorage.setItem('admin_lastActivity', timestamp);
                    setTimeout(() => window.location.href = 'admin.html', 1000);
                } else if (role === 'DRIVER') {
                    localStorage.setItem('driver_sessionId', data.sessionId);
                    localStorage.setItem('driver_username', user);
                    localStorage.setItem('driver_email', data.email || 'No Email');
                    localStorage.setItem('driver_model', data.vehicle_model || 'N/A');
                    localStorage.setItem('driver_plate', data.license_plate || 'N/A');
                    localStorage.setItem('driver_rating', data.rating || '5.0');
                    localStorage.setItem('driver_uid', data.userId);
                    localStorage.setItem('driver_lastActivity', timestamp);
                    setTimeout(() => window.location.href = 'driver.html', 1000);
                } else {
                    localStorage.setItem('passenger_sessionId', data.sessionId);
                    localStorage.setItem('passenger_userId', data.userId);
                    localStorage.setItem('passenger_username', user);
                    localStorage.setItem('passenger_lastActivity', timestamp);
                    setTimeout(() => window.location.href = 'passenger.html', 1000);
                }
            } else {
                throw new Error(data.message || "Invalid username or password credentials.");
            }
        } else {
            // REGISTRATION FLOW (Passenger vs Driver selected by tab)
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
