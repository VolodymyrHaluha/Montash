# Montash integration with the existing FROP servers

Run the existing GLOBAL/app.py and LOCAL/app.py in D:\virtual environment\myenv\FROP-SERVER-TEST-V with their own run_global.bat and run_local.bat. The Montash repository has no separate server process.

server_montash/app.py is a Flask route extension. install_existing_servers.py copies it into both existing server folders as montash_api.py and registers it in each app.py. The original app.py files are backed up as app.py.pre-montash. Run the installer again after updating the extension:

    python server_montash/install_existing_servers.py "D:\virtual environment\myenv\FROP-SERVER-TEST-V"

The installer adds MONTASH_CLIENT_TOKEN to both private server .env files from the Android project's private root .env. GLOBAL_SYNC_TOKEN protects server-to-server sync. Current .env settings use GLOBAL port 5200, matching the Cloudflare Tunnel origin, while LOCAL pulls GLOBAL through 127.0.0.1:5200. Android uses https://frop-attendance.date for GLOBAL and the computer's LAN address on port 5101 for LOCAL. All addresses remain in .env files; if the LAN IP changes, update the Android root .env and rebuild.

Montash endpoints are /api/montash/health, /api/montash/projects, and /api/montash/sync/changes. GLOBAL accepts object writes with revision checking; LOCAL pulls them and retries if GLOBAL starts later. The existing attendance routes and tunnel lifecycle remain in their original servers.

Montash metadata uses separate montash_projects, montash_changes, and montash_sync_state tables in the existing PostgreSQL databases. Media files themselves still need an upload/download implementation; JSON currently carries only local path metadata.
