"""Update Montash routes in the existing FROP GLOBAL and LOCAL servers."""

import subprocess
import sys
from pathlib import Path

from dotenv import dotenv_values


project = Path(__file__).resolve().parent
source_value = dotenv_values(project / ".env").get("MONTASH_SERVER_SOURCE_DIR")
if not source_value:
    raise SystemExit("MONTASH_SERVER_SOURCE_DIR is required in Montash/.env")

source = Path(source_value).expanduser().resolve()
installer = source / "install_existing_servers.py"
server_root = source.parent
if not installer.is_file() or not (server_root / "GLOBAL" / "app.py").is_file() or not (server_root / "LOCAL" / "app.py").is_file():
    raise SystemExit(f"Invalid FROP server source directory: {source}")

subprocess.run([sys.executable, str(installer), str(server_root)], check=True)
