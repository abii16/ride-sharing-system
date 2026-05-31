        async function register() {
            const user = document.getElementById('username').value;
            const email = document.getElementById('email').value;
            const pass = document.getElementById('password').value;
            const model = document.getElementById('model').value;
            const plate = document.getElementById('plate').value;
            const btn = document.getElementById('subBtn');
            const msg = document.getElementById('msgBox');
            
            msg.style.display = 'none';
            btn.disabled = true;
            btn.innerText = "Processing...";
            
            const payload = {
                username: user,
                email: email,
                password: pass,
                role: 'DRIVER',
                vehicle_model: model,
                license_plate: plate
            };

            try {
                const res = await fetch('/api/register', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                const data = await res.json();
                
                if (data.success) {
                    showMsg("Application successful! Redirecting...", "success");
                    setTimeout(() => {
                        window.location.href = 'driver.html';
                    }, 1500);
                } else {
                    showMsg(data.message || "Registration failed", "error");
                    btn.disabled = false;
                    btn.innerText = "Submit Driver Application";
                }
            } catch (e) {
                showMsg("Connection failed: " + e.message, "error");
                btn.disabled = false;
            }
        }

        function showMsg(text, type) {
            const msg = document.getElementById('msgBox');
            msg.innerText = text;
            msg.className = 'alert ' + (type === 'success' ? 'alert-success' : 'alert-error');
            msg.style.display = 'block';
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
