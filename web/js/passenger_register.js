        async function register() {
            const user = document.getElementById('username').value;
            const pass = document.getElementById('password').value;
            const btn = document.getElementById('subBtn');
            const msg = document.getElementById('msgBox');
            
            msg.style.display = 'none';
            btn.disabled = true;
            btn.innerText = "Processing...";
            
            const payload = {
                username: user,
                password: pass,
                role: 'PASSENGER'
            };

            try {
                const res = await fetch('/api/register', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                const data = await res.json();
                
                if (data.success) {
                    showMsg("Registration successful! Redirecting...", "success");
                    setTimeout(() => {
                        window.location.href = 'passenger.html';
                    }, 1500);
                } else {
                    showMsg(data.message || "Registration failed", "error");
                    btn.disabled = false;
                    btn.innerText = "Sign Up";
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
