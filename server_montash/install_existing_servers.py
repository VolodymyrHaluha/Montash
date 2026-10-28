"""Install Montash routes into the existing FROP GLOBAL and LOCAL servers.

Run with the absolute FROP-SERVER-TEST-V directory as the only argument.
The installer keeps one original app.py backup per server.
"""

import shutil
import sys
from pathlib import Path

from dotenv import dotenv_values, set_key


def replace_once(source, old, new, label):
    if old not in source:
        raise RuntimeError(f"Cannot find insertion point: {label}")
    return source.replace(old, new, 1)


def install(root):
    project = Path(__file__).resolve().parents[1]
    extension = project / "server_montash" / "app.py"
    if not extension.is_file():
        raise RuntimeError("Montash extension source is missing")
    client_token = dotenv_values(project / ".env").get("MONTASH_CLIENT_API_TOKEN")
    if not client_token:
        raise RuntimeError("MONTASH_CLIENT_API_TOKEN is missing in Montash/.env")

    global_dir = root / "GLOBAL"
    local_dir = root / "LOCAL"
    for folder in (global_dir, local_dir):
        if not (folder / "app.py").is_file() or not (folder / ".env").is_file():
            raise RuntimeError(f"Expected app.py and .env in {folder}")

    global_file = global_dir / "app.py"
    local_file = local_dir / "app.py"
    global_bytes = global_file.read_bytes()
    local_bytes = local_file.read_bytes()
    global_crlf = b"\r\n" in global_bytes
    local_crlf = b"\r\n" in local_bytes
    global_code = global_bytes.decode("utf-8").replace("\r\n", "\n")
    local_code = local_bytes.decode("utf-8").replace("\r\n", "\n")
    if "# Montash integration" not in global_code:
        global_code = replace_once(
            global_code,
            "from flask import Flask, request, jsonify, send_file\n",
            "from flask import Flask, request, jsonify, send_file\n"
            "from montash_api import register as register_montash  # Montash integration\n",
            "GLOBAL import",
        )
        global_code = replace_once(
            global_code,
            "\n\ndef apply_global_migrations() -> None:",
            "\n\nmontash_init, _montash_unused_worker = register_montash(\n"
            "    app, get_db, mode=\"global\",\n"
            "    client_token=os.environ.get(\"MONTASH_CLIENT_TOKEN\", \"\").strip(),\n"
            "    sync_token=GLOBAL_SYNC_TOKEN, migration_db=get_migration_db,\n"
            ")\n\n"
            "def apply_global_migrations() -> None:",
            "GLOBAL registration",
        )
        global_code = replace_once(
            global_code,
            "        cwd=BASE_DIR,\n",
            "        cwd=BASE_DIR,\n"
            "        env={key: value for key, value in os.environ.items() if key not in {\n"
            "            \"CLOUDFLARE_TUNNEL_TOKEN\", \"DATABASE_URL\", \"GLOBAL_SYNC_TOKEN\",\n"
            "            \"MONTASH_CLIENT_TOKEN\",\n"
            "        }},\n",
            "GLOBAL tunnel environment",
        )
    if "# Montash integration" not in local_code:
        local_code = replace_once(
            local_code,
            "from flask import Flask, render_template, request, jsonify, send_file, send_from_directory\n",
            "from flask import Flask, render_template, request, jsonify, send_file, send_from_directory\n"
            "from montash_api import register as register_montash  # Montash integration\n",
            "LOCAL import",
        )
        local_code = replace_once(
            local_code,
            "\n\ndef apply_local_migrations() -> None:",
            "\n\nmontash_init, montash_start_worker = register_montash(\n"
            "    app, get_db, mode=\"local\",\n"
            "    client_token=os.environ.get(\"MONTASH_CLIENT_TOKEN\", \"\").strip(),\n"
            "    sync_token=GLOBAL_SYNC_TOKEN, global_url=GLOBAL_SERVER_URL,\n"
            "    auto_sync=AUTO_SYNC_ENABLED, sync_on_startup=AUTO_SYNC_RUN_ON_STARTUP,\n"
            "    sync_interval=AUTO_SYNC_INTERVAL_SECONDS,\n"
            ")\n\n"
            "def apply_local_migrations() -> None:",
            "LOCAL registration",
        )
        local_code = replace_once(
            local_code,
            "    apply_local_migrations()\n    start_auto_sync_if_needed()\n",
            "    apply_local_migrations()\n    start_auto_sync_if_needed()\n"
            "    montash_start_worker()\n",
            "LOCAL request worker",
        )
        local_code = replace_once(
            local_code,
            "        start_auto_sync_if_needed()\n\n    app.run(",
            "        start_auto_sync_if_needed()\n        montash_start_worker()\n\n    app.run(",
            "LOCAL startup worker",
        )
    if 'app.run(host="0.0.0.0", port=5101, debug=True)' in local_code:
        local_code = replace_once(
            local_code,
            'app.run(host="0.0.0.0", port=5101, debug=True)',
            'app.run(host="0.0.0.0", port=int(os.environ.get("APP_PORT", "5101")), '
            'debug=False, use_reloader=False)',
            "LOCAL production run mode",
        )

    for path, code, crlf in ((global_file, global_code, global_crlf),
                             (local_file, local_code, local_crlf)):
        backup = path.with_name("app.py.pre-montash")
        if not backup.exists():
            shutil.copy2(path, backup)
        path.write_bytes(code.replace("\n", "\r\n" if crlf else "\n").encode("utf-8"))
        shutil.copy2(extension, path.with_name("montash_api.py"))

    set_key(str(global_dir / ".env"), "MONTASH_CLIENT_TOKEN", client_token, quote_mode="never")
    set_key(str(local_dir / ".env"), "MONTASH_CLIENT_TOKEN", client_token, quote_mode="never")
    print("Montash routes installed in existing GLOBAL and LOCAL servers.")
    print("Server ports and URLs remain in their .env files; Android URLs remain in Montash/.env.")
    print("Original app.py files saved as app.py.pre-montash.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python install_existing_servers.py <FROP-SERVER-TEST-V directory>")
    install(Path(sys.argv[1]).resolve())
