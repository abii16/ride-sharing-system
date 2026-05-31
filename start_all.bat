@echo off

REM Prefer mysql.exe from PATH; fall back to common XAMPP location.
set "MYSQL_CMD=mysql"
where mysql >nul 2>&1
if not errorlevel 1 goto :mysql_detected
if exist "C:\xampp\mysql\bin\mysql.exe" set "MYSQL_CMD=C:\xampp\mysql\bin\mysql.exe"
:mysql_detected

echo Compiling source code...
javac -cp "lib/json.jar;lib/mysql-connector.jar;src" src/com/rideshare/web/WebGateway.java src/com/rideshare/dispatch/DispatchServer.java src/com/rideshare/driver/DriverService.java src/com/rideshare/database/DatabaseService.java
if errorlevel 1 (
    echo Compilation failed!
    pause
    exit /b %errorlevel%
)

echo Killing old processes...


taskkill /F /IM java.exe 2>nul

echo ====================================================
echo   DATABASE SETUP (LEADER)
echo ====================================================

"%MYSQL_CMD%" --version >nul 2>&1
if errorlevel 1 goto :mysql_missing

"%MYSQL_CMD%" -u root -e "SELECT 1;" >nul 2>&1
if errorlevel 1 goto :mysql_cannot_connect

echo Creating database and loading schema...
"%MYSQL_CMD%" -u root -e "CREATE DATABASE IF NOT EXISTS rideshare_db; USE rideshare_db; source docs/schema.sql;" >nul 2>&1
if errorlevel 1 (
    echo [WARNING] Could not auto-load schema.sql. You may need to run docs/schema.sql manually.
) else (
    echo [OK] rideshare_db is ready.
)
goto :mysql_done

:mysql_missing
echo [WARNING] MySQL client not found. If DatabaseService fails, install/start MySQL (XAMPP).
goto :mysql_done

:mysql_cannot_connect
echo [ERROR] Cannot connect to local MySQL as root.
echo Start MySQL in XAMPP Control Panel, then rerun start_all.bat.
pause
exit /b 1

:mysql_done




echo Starting Database Service (Port 7000)...
start /B "Database Service" java -cp "lib/json.jar;lib/mysql-connector.jar;src" com.rideshare.database.DatabaseService

timeout /t 3 >nul

echo Starting Driver Service (Port 6000)...
start /B "Driver Service" java -cp "lib/json.jar;src" com.rideshare.driver.DriverService

timeout /t 2 >nul

echo Starting Dispatch Server (Port 5000)...
start /B "Dispatch Server" java -cp "lib/json.jar;src" com.rideshare.dispatch.DispatchServer

timeout /t 2 >nul

echo Starting Web Gateway (Port 8080)...
start /B "Web Gateway" java -cp "lib/json.jar;src" com.rideshare.web.WebGateway

echo.
echo ====================================================
echo   SYSTEM IS LIVE
echo.
echo   Share this URL with others on your network:
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr "IPv4"') do set IP=%%a
set IP=%IP:~1%

REM Try to configure MySQL remote access automatically silently
"%MYSQL_CMD%" -u root -e "CREATE USER IF NOT EXISTS 'root'@'%%' IDENTIFIED BY ''; GRANT ALL PRIVILEGES ON *.* TO 'root'@'%%' WITH GRANT OPTION; FLUSH PRIVILEGES;" >nul 2>&1

echo   http://%IP%:8080/
echo.

echo ====================================================
pause