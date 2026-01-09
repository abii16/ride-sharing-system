@echo off
setlocal EnableDelayedExpansion

pushd "%~dp0"

REM Prefer mysql.exe from PATH; fall back to common XAMPP location.
set "MYSQL_CMD=mysql"
where mysql >nul 2>&1
if errorlevel 1 (
    if exist "C:\xampp\mysql\bin\mysql.exe" set "MYSQL_CMD=C:\xampp\mysql\bin\mysql.exe"
)

echo ====================================================
echo   JOIN CLUSTER ^(MULTI-NODE SUPPORT^)
echo ====================================================
echo .

:: 1. Check for Java Runtime
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java is not installed or not in PATH.
    echo Please install Java 17+ and try again.
    pause
    exit /b
)

:: 2. Check for MySQL (for auto-setup)
"!MYSQL_CMD!" --version >nul 2>&1
if not errorlevel 1 (
    echo [INFO] MySQL command line tool found.
) else (
    echo [INFO] MySQL command line not in PATH. Auto-DB creation might fail.
)

:ASK_ID
set /p "NODE_ID=Enter THIS Node ID ^(Integer, e.g., 2 or 3^): "
if "!NODE_ID!"=="" (
    echo Error: Node ID is required.
    goto ASK_ID
)

REM Trim leading/trailing whitespace
for /f "tokens=* delims= " %%A in ("!NODE_ID!") do set "NODE_ID=%%A"

REM Validate it is digits only
echo(!NODE_ID!| findstr /r "^[0-9][0-9]*$" >nul
if errorlevel 1 (
    echo Error: Node ID must be a positive integer.
    goto ASK_ID
)

set /a PORT=7000 + NODE_ID - 1
set /a CLUSTER_PORT=8000 + NODE_ID - 1
set /a DISPATCH_PORT=5000 + NODE_ID - 1
set /a DRIVER_PORT=6000 + NODE_ID - 1
set /a WEB_PORT=8080 + NODE_ID - 1

:ASK_IP
set /p "LEADER_IP=Enter the MASTER NODE IP : "
if "!LEADER_IP!"=="" (
    echo Error: Master Node IP is required.
    goto ASK_IP
)

echo.
echo Checking connection to Leader...
ping -n 1 "!LEADER_IP!" >nul
if errorlevel 1 (
    echo [WARNING] Cannot ping Master Node IP !LEADER_IP!.
    echo Check Firewall settings on BOTH PCs ^(Allow Java/Port 8000^).
    echo Ensure both PCs are on the same Wi-Fi/Network.
    echo.
    set /p "IGNORE_PING=Continue anyway? ^(Y/N^): "
    if /I "!IGNORE_PING!" NEQ "Y" (
        echo Aborting setup due to connectivity issues.
        pause
        exit /b
    )
    echo.
) else (
    echo [OK] Master Node is reachable.
)

REM Database Mode
echo ====================================================
echo   DATABASE MODE
echo ====================================================
echo If you want full HA, install MySQL locally on each node.
echo Otherwise, this node can use the Leader's MySQL remotely.
echo.

:ASK_DB_MODE
set "DEFAULT_HAS_LOCAL=N"
if /I "!MYSQL_CMD!"=="C:\xampp\mysql\bin\mysql.exe" set "DEFAULT_HAS_LOCAL=Y"
set /p "HAS_LOCAL_MYSQL=Local MySQL on THIS node? ^(Y/N, default !DEFAULT_HAS_LOCAL!^): "
if "!HAS_LOCAL_MYSQL!"=="" set "HAS_LOCAL_MYSQL=!DEFAULT_HAS_LOCAL!"

set DB_USER=root
set DB_PASS=

if /I "!HAS_LOCAL_MYSQL!"=="Y" goto LOCAL_DB
if /I "!HAS_LOCAL_MYSQL!"=="N" goto SHARED_DB

echo Please enter Y or N.
goto ASK_DB_MODE

:SHARED_DB
echo.
echo [INFO] Using Leader MySQL over the network.

REM Default credentials for Leader MySQL (matches the provided guide).
REM Optional overrides (non-interactive):
REM   set RS_DB_USER=someuser
REM   set RS_DB_PASS=somepass
if defined RS_DB_USER (set "DB_USER=!RS_DB_USER!") else (set "DB_USER=root")
if defined RS_DB_PASS (set "DB_PASS=!RS_DB_PASS!") else (set "DB_PASS=")

set "DB_URL=jdbc:mysql://!LEADER_IP!:3306/rideshare_db"
echo [INFO] DB URL: !DB_URL!
goto AFTER_DB_SETUP

:LOCAL_DB
echo.
echo ====================================================
echo   LOCAL DATABASE CONFIGURATION
echo ====================================================

"!MYSQL_CMD!" --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] MySQL command line tool not found!
    echo Install MySQL ^(e.g. XAMPP^) and add it to PATH.
    pause
    exit /b
)

:MSG_DB_SETUP
echo.
echo Attempting to set up local database...
echo Default credentials: User='root', Password=''

REM Quick connectivity check (MySQL must be running)
"!MYSQL_CMD!" -u !DB_USER! --password="!DB_PASS!" -e "SELECT 1;" >nul 2>&1
if errorlevel 1 (
    echo.
    echo [ERROR] Cannot connect to local MySQL.
    echo Start MySQL first ^(XAMPP Control Panel -^> Start MySQL^), then rerun.
    pause
    exit /b
)

:: Try to create DB with default credentials
"!MYSQL_CMD!" -u !DB_USER! --password="!DB_PASS!" -e "CREATE DATABASE IF NOT EXISTS rideshare_db; USE rideshare_db; source docs/schema.sql;" >nul 2>&1

if errorlevel 1 (
    echo.
    echo [WARNING] Could not connect to local MySQL with user '!DB_USER!'.
    set /p "DB_USER=Enter MySQL Username ^(default: root^): "
    if "!DB_USER!"=="" set "DB_USER=root"
    set /p "DB_PASS=Enter MySQL Password ^(blank if none^): "

    echo Retrying...
    "!MYSQL_CMD!" -u !DB_USER! --password="!DB_PASS!" -e "CREATE DATABASE IF NOT EXISTS rideshare_db; USE rideshare_db; source docs/schema.sql;"

    if errorlevel 1 (
        echo.
        echo [ERROR] Failed to set up local database.
        echo Ensure MySQL is running and credentials are correct.
        pause
        goto MSG_DB_SETUP
    )
)

echo [OK] Local database 'rideshare_db' is ready.
set "DB_URL=jdbc:mysql://localhost:3306/rideshare_db"

:AFTER_DB_SETUP

echo Killing old processes...
taskkill /F /IM java.exe 2>nul
echo kill /F /IM java.exe 2>nul
echo.

javac -version >nul 2>&1
if errorlevel 1 (
    echo [WARNING] 'javac' ^(Java Compiler^) not found.
    echo Assuming binary .class files were included in the download.
    echo Skipping compilation...
) else (
    echo Compiling source code...
    javac -cp "lib/json.jar;lib/mysql-connector.jar;src" src/com/rideshare/web/WebGateway.java src/com/rideshare/dispatch/DispatchServer.java src/com/rideshare/driver/DriverService.java src/com/rideshare/database/DatabaseService.java
    if errorlevel 1 (
        echo [ERROR] Compilation failed!
        pause
        exit /b !errorlevel!
    )
)

echo Starting Database Service (Node !NODE_ID!)...
echo Connecting to Cluster Master Node at !LEADER_IP!...

start "Database Service (Node !NODE_ID!)" cmd /k java -Ddb.url="!DB_URL!" -Ddb.user="!DB_USER!" -Ddb.pass="!DB_PASS!" -cp "lib/json.jar;lib/mysql-connector.jar;src" com.rideshare.database.DatabaseService !NODE_ID! !PORT! !CLUSTER_PORT! 1:!LEADER_IP!:8000:7000

timeout /t 5 >nul

echo Starting Driver Service...
start "Driver Service" cmd /k java -cp "lib/json.jar;src" com.rideshare.driver.DriverService !DRIVER_PORT!

timeout /t 2 >nul

echo Starting Dispatch Server...
start "Dispatch Server" cmd /k java -cp "lib/json.jar;src" com.rideshare.dispatch.DispatchServer !DISPATCH_PORT! !PORT! !DRIVER_PORT!

timeout /t 2 >nul

echo Starting Web Gateway...
start "Web Gateway" cmd /k java -cp "lib/json.jar;src" com.rideshare.web.WebGateway !WEB_PORT! !DISPATCH_PORT! !DRIVER_PORT!

echo.
echo ====================================================
echo   BACKUP NODE RUNNING
echo   
echo   Access this Backup Node at:
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr "IPv4"') do set IP=%%a
set "IP=!IP:~1!"
echo   http://!IP!:!WEB_PORT!
echo.
echo   [DEBUG INFO]
echo   DB URL: !DB_URL!
echo   Leader: !LEADER_IP!
echo.
echo ====================================================
pause

popd
