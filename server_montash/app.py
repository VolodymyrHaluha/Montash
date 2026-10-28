"""Montash API extension for the existing FROP GLOBAL and LOCAL Flask apps."""

import threading
import time
from contextlib import closing, contextmanager
from hmac import compare_digest

from flask import jsonify, request
from psycopg2.extras import Json
import requests


def valid_project(data):
    if not isinstance(data, dict):
        raise ValueError("Object must be a JSON object")
    if not isinstance(data.get("id"), str) or not data["id"].strip() or len(data["id"]) > 200:
        raise ValueError("Object ID must be a nonempty string")
    if not isinstance(data.get("name"), str) or not isinstance(data.get("customer"), str):
        raise ValueError("name and customer must be strings")
    if not isinstance(data.get("blocks"), list):
        raise ValueError("blocks must be an array")
    seen = set()
    for block in data["blocks"]:
        if not isinstance(block, dict):
            raise ValueError("Each product must be an object")
        block_id = block.get("id")
        if not isinstance(block_id, str) or not block_id.strip() or len(block_id) > 200:
            raise ValueError("Product ID must be a nonempty string")
        if block_id in seen:
            raise ValueError("Duplicate product ID")
        seen.add(block_id)
        if block.get("status") not in {"DRAFT", "IN_PROGRESS", "DONE"}:
            raise ValueError("Unknown product status")
        for field in ("photos", "media"):
            if not isinstance(block.get(field), list):
                raise ValueError(f"{field} must be an array")
    return data["id"]


def register(app, get_db, *, mode, client_token, sync_token,
             global_url=None, migration_db=None, auto_sync=False,
             sync_on_startup=False, sync_interval=600):
    if mode not in {"global", "local"} or not client_token or not sync_token:
        raise RuntimeError("Montash mode, MONTASH_CLIENT_TOKEN and GLOBAL_SYNC_TOKEN are required")
    if mode == "local" and not global_url:
        raise RuntimeError("LOCAL requires GLOBAL_SERVER_URL")
    global_url = global_url.rstrip("/") if global_url else None
    migration_db = migration_db or get_db
    schema_lock = threading.Lock()
    sync_lock = threading.Lock()
    worker_lock = threading.Lock()
    schema_ready = False
    worker_started = False

    @contextmanager
    def transaction(connect):
        with closing(connect()) as conn, conn, conn.cursor() as cur:
            yield cur

    def ensure_schema():
        nonlocal schema_ready
        if schema_ready:
            return
        with schema_lock:
            if schema_ready:
                return
            with transaction(migration_db) as cur:
                cur.execute("""
                    CREATE TABLE IF NOT EXISTS montash_projects (
                        id text PRIMARY KEY, data jsonb NOT NULL,
                        revision bigint NOT NULL, deleted boolean NOT NULL DEFAULT false
                    )
                """)
                cur.execute("""
                    CREATE TABLE IF NOT EXISTS montash_changes (
                        sequence bigserial PRIMARY KEY, project_id text NOT NULL,
                        data jsonb, deleted boolean NOT NULL DEFAULT false
                    )
                """)
                cur.execute("""
                    CREATE TABLE IF NOT EXISTS montash_sync_state (
                        name text PRIMARY KEY, sequence bigint NOT NULL
                    )
                """)
            schema_ready = True

    def authorized(token):
        return compare_digest(request.headers.get("Authorization", ""), f"Bearer {token}")

    @app.before_request
    def prepare_montash():
        if request.path.startswith("/api/montash/"):
            ensure_schema()

    @app.get("/api/montash/health")
    def montash_health():
        if not authorized(client_token):
            return jsonify(ok=False, error="Unauthorized"), 401
        with transaction(get_db) as cur:
            cur.execute("SELECT 1")
        return jsonify(ok=True, mode=mode)

    @app.get("/api/montash/projects")
    def montash_projects():
        if not authorized(client_token):
            return jsonify(ok=False, error="Unauthorized"), 401
        with transaction(get_db) as cur:
            cur.execute("SELECT data, revision FROM montash_projects WHERE NOT deleted ORDER BY id")
            rows = cur.fetchall()
        return jsonify(ok=True, projects=[dict(row["data"], revision=row["revision"]) for row in rows])

    @app.put("/api/montash/projects/<project_id>")
    def montash_put_project(project_id):
        if mode != "global":
            return jsonify(ok=False, error="Writes belong on GLOBAL"), 405
        if not authorized(client_token):
            return jsonify(ok=False, error="Unauthorized"), 401
        data = request.get_json(silent=True)
        try:
            if valid_project(data) != project_id:
                raise ValueError("Path ID differs from object ID")
            expected = data.pop("revision", None)
            if type(expected) is not int or expected < 0:
                raise ValueError("revision must be a nonnegative integer")
        except (ValueError, TypeError) as error:
            return jsonify(ok=False, error=str(error)), 400
        with transaction(get_db) as cur:
            cur.execute("SELECT pg_advisory_xact_lock(71234562)")
            cur.execute("SELECT revision FROM montash_projects WHERE id=%s FOR UPDATE", (project_id,))
            previous = cur.fetchone()
            actual = previous["revision"] if previous else 0
            if expected != actual:
                return jsonify(ok=False, error="Revision conflict", revision=actual), 409
            revision = actual + 1
            cur.execute("""
                INSERT INTO montash_projects(id, data, revision, deleted)
                VALUES (%s, %s, %s, false)
                ON CONFLICT (id) DO UPDATE SET data=EXCLUDED.data,
                    revision=EXCLUDED.revision, deleted=false
            """, (project_id, Json(data), revision))
            cur.execute("INSERT INTO montash_changes(project_id, data) VALUES (%s, %s)",
                        (project_id, Json(data)))
        return jsonify(ok=True, revision=revision)

    @app.delete("/api/montash/projects/<project_id>")
    def montash_delete_project(project_id):
        if mode != "global":
            return jsonify(ok=False, error="Writes belong on GLOBAL"), 405
        if not authorized(client_token):
            return jsonify(ok=False, error="Unauthorized"), 401
        with transaction(get_db) as cur:
            cur.execute("SELECT pg_advisory_xact_lock(71234562)")
            cur.execute("UPDATE montash_projects SET deleted=true, revision=revision+1 WHERE id=%s RETURNING id", (project_id,))
            if not cur.fetchone():
                return jsonify(ok=False, error="Object not found"), 404
            cur.execute("INSERT INTO montash_changes(project_id, deleted) VALUES (%s, true)", (project_id,))
        return jsonify(ok=True)

    @app.get("/api/montash/sync/changes")
    def montash_changes():
        if mode != "global":
            return jsonify(ok=False, error="Only GLOBAL provides changes"), 404
        if not authorized(sync_token):
            return jsonify(ok=False, error="Unauthorized"), 401
        try:
            after = max(0, int(request.args.get("after", "0")))
        except ValueError:
            return jsonify(ok=False, error="Invalid cursor"), 400
        with transaction(get_db) as cur:
            cur.execute("""
                SELECT sequence, project_id, data, deleted FROM montash_changes
                WHERE sequence > %s ORDER BY sequence LIMIT 100
            """, (after,))
            rows = cur.fetchall()
        return jsonify(ok=True, changes=[dict(row) for row in rows])

    def sync_once():
        if mode != "local":
            raise RuntimeError("Synchronization runs only on LOCAL")
        ensure_schema()
        if not sync_lock.acquire(blocking=False):
            return {"ok": False, "error": "Synchronization is already running"}
        try:
            with transaction(get_db) as cur:
                cur.execute("SELECT sequence FROM montash_sync_state WHERE name='global'")
                row = cur.fetchone()
                after = row["sequence"] if row else 0
            session = requests.Session()
            session.trust_env = False
            try:
                response = session.get(
                    f"{global_url}/api/montash/sync/changes",
                    headers={"Authorization": f"Bearer {sync_token}"},
                    params={"after": after}, timeout=20,
                )
                response.raise_for_status()
                changes = response.json()["changes"]
            finally:
                session.close()
            with transaction(get_db) as cur:
                for change in changes:
                    project_id = change["project_id"]
                    if not isinstance(project_id, str) or not project_id.strip():
                        raise ValueError("Invalid project ID in GLOBAL response")
                    sequence = int(change["sequence"])
                    if sequence <= after:
                        continue
                    if change["deleted"]:
                        cur.execute("""
                            INSERT INTO montash_projects(id, data, revision, deleted)
                            VALUES (%s, '{}'::jsonb, %s, true)
                            ON CONFLICT (id) DO UPDATE SET deleted=true, revision=EXCLUDED.revision
                        """, (project_id, sequence))
                    else:
                        data = change["data"]
                        if valid_project(data) != project_id:
                            raise ValueError("Invalid project in GLOBAL response")
                        cur.execute("""
                            INSERT INTO montash_projects(id, data, revision, deleted)
                            VALUES (%s, %s, %s, false)
                            ON CONFLICT (id) DO UPDATE SET data=EXCLUDED.data,
                                revision=EXCLUDED.revision, deleted=false
                        """, (project_id, Json(data), sequence))
                    after = sequence
                cur.execute("""
                    INSERT INTO montash_sync_state(name, sequence) VALUES ('global', %s)
                    ON CONFLICT (name) DO UPDATE SET sequence=EXCLUDED.sequence
                """, (after,))
            return {"ok": True, "imported": len(changes), "cursor": after}
        finally:
            sync_lock.release()

    @app.post("/api/montash/sync/from-global")
    def montash_sync_now():
        if mode != "local":
            return jsonify(ok=False, error="Only LOCAL pulls from GLOBAL"), 404
        if not authorized(sync_token):
            return jsonify(ok=False, error="Unauthorized"), 401
        try:
            return jsonify(sync_once())
        except (requests.RequestException, ValueError, KeyError) as error:
            app.logger.warning("Montash sync failed: %s", error)
            return jsonify(ok=False, error=str(error)), 502

    def worker():
        first = True
        previous_error = None
        while True:
            time.sleep(0 if first and sync_on_startup else 5 if previous_error else sync_interval)
            first = False
            try:
                result = sync_once()
                if not result.get("ok"):
                    raise RuntimeError(result.get("error", "Synchronization failed"))
                if previous_error:
                    app.logger.info("Montash GLOBAL connection restored")
                previous_error = None
            except requests.RequestException as error:
                message = type(error).__name__
                if message != previous_error:
                    app.logger.warning("Montash GLOBAL unavailable; retrying in 5 seconds")
                previous_error = message
            except Exception:
                app.logger.exception("Montash sync failed")
                previous_error = "other error"

    def start_worker():
        nonlocal worker_started
        if mode != "local" or not auto_sync or worker_started:
            return
        with worker_lock:
            if not worker_started:
                worker_started = True
                threading.Thread(target=worker, name="montash-sync", daemon=True).start()

    return ensure_schema, start_worker
