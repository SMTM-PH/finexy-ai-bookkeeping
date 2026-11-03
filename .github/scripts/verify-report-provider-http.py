#!/usr/bin/env python3
"""Run the unchanged report HTTP verifier against real disposable providers.

Each PostgreSQL/MySQL on/off round gets a fresh schema, application, random
database credentials and users. No database ports or host data are exposed.
This runner never starts/restarts Docker Desktop or operates user services.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request


ROOT = Path(__file__).resolve().parents[2]
DOCKER = os.environ.get("FINEXY_DOCKER", r"C:\Users\abc123\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe")
PREFIX = "finexy-report-provider-20261022-http-"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="artifacts/ledger-report-api-http-providers-20261022")
    parser.add_argument("--image", default="finexy-report-api:20261022-providers-root1")
    parser.add_argument("--source-summary", default="artifacts/ledger-report-providers-20261022/final-complete/summary.json")
    parser.add_argument("--runtime-summary", default="artifacts/ledger-report-api-providers-20261022/runtime-summary.json")
    args = parser.parse_args()
    relative_output = Path(args.output).as_posix()
    if not relative_output.startswith("artifacts/ledger-report-api-"):
        raise SystemExit("Evidence must use the disposable report artifact prefix")
    output = (ROOT / args.output).resolve()
    if not output.is_relative_to(ROOT / "artifacts"):
        raise SystemExit("Evidence must remain inside the workspace artifacts directory")
    output.mkdir(parents=True, exist_ok=True)
    if (output / "summary.json").exists():
        raise SystemExit("Choose a new evidence directory; previous results are preserved")
    source_summary = json.loads((ROOT / args.source_summary).read_text(encoding="utf-8"))
    app_summary = json.loads((ROOT / args.runtime_summary).read_text(encoding="utf-8-sig"))
    if source_summary.get("complete") is not True:
        raise SystemExit("The referenced source/provider/build candidate did not pass its gates")
    image_id = app_summary["imageId"]
    expected_binary = source_summary["binarySHA256"]
    db_images = {p: next(c["image"] for c in source_summary["containers"] if "-" + p + "-" in c["name"])
                 for p in ["postgres", "mysql"]}
    suffix = secrets.token_hex(4)
    network = PREFIX + suffix
    owned, cleanup = [], []
    summary = {"scope": "real PostgreSQL/MySQL-backed report HTTP export on/off",
               "state": "not_started", "imageId": image_id, "binarySHA256": expected_binary,
               "sourceManifestSHA256": source_summary["sourceManifestSHA256"], "rounds": [], "containers": [],
               "credentialsPersisted": False, "network": network,
               "notCovered": ["production deployment", "browser/Android report UI", "other database versions/topologies", "production-scale data"]}
    network_created = False

    def progress(stage, **safe):
        summary["lastStage"] = stage
        (output / "progress.json").write_text(json.dumps({"pid": os.getpid(), "stage": stage, "unixSeconds": int(time.time()), "network": network, **safe}, indent=2), encoding="utf-8")

    def docker(*argv, **kwargs):
        return subprocess.run([DOCKER, *argv], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **kwargs)

    def checked(*argv, **kwargs):
        result = docker(*argv, **kwargs)
        if result.returncode:
            # Environment arguments contain temporary credentials. Never emit
            # command arguments or Docker startup output into persisted logs.
            raise RuntimeError("owned Docker operation failed")
        return result.stdout.decode(errors="replace").strip()

    def inspect(cid, application=False, port=None):
        raw = json.loads(checked("inspect", cid))[0]
        safe = {"id": raw["Id"], "name": raw["Name"], "image": raw["Image"], "mounts": raw["Mounts"],
                "autoRemove": raw["HostConfig"]["AutoRemove"], "portBindings": raw["HostConfig"]["PortBindings"]}
        assert safe["mounts"] == [] and safe["autoRemove"] is True
        if application:
            assert safe["image"] == image_id
            assert safe["portBindings"] == {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": str(port)}]}
        else:
            assert not safe["portBindings"]
        summary["containers"].append(safe)
        return raw

    def stop(cid):
        stopped = docker("stop", "-t", "10", cid)
        exists = docker("inspect", cid)
        for _ in range(30):
            if exists.returncode:
                break
            time.sleep(0.1)
            exists = docker("inspect", cid)
        cleanup.append({"id": cid, "stopExitCode": stopped.returncode, "absent": exists.returncode != 0})
        owned.remove(cid)

    try:
        progress("docker_preflight")
        print("START isolated HTTP runner pid=" + str(os.getpid()), flush=True)
        probe = docker("info", "--format", "{{.ServerVersion}}")
        summary["dockerPreflightExitCode"] = probe.returncode
        if probe.returncode:
            summary["state"] = "blocked"
            summary["blockedReason"] = "Docker engine is unavailable; no restart or service operation was attempted"
            return 2
        summary["dockerVersion"] = probe.stdout.decode().strip()
        actual_image = json.loads(checked("image", "inspect", args.image))[0]
        assert actual_image["Id"] == image_id, "candidate image changed from the frozen report candidate"
        for candidate in [actual_image] + [json.loads(checked("image", "inspect", name))[0] for name in db_images.values()]:
            assert not candidate["Config"].get("Volumes"), "no inherited anonymous volumes are permitted"
        for port in [18127, 18128]:
            with socket.socket() as listener:
                listener.bind(("127.0.0.1", port))
        summary["sourceHashes"] = {str(path.relative_to(ROOT)).replace("\\", "/"): hashlib.sha256(path.read_bytes()).hexdigest()
                                   for path in [Path(__file__).resolve(), ROOT / ".github/scripts/verify-ledger-report.mjs",
                                                ROOT / "testdata/ledger_report/fixture.mjs", ROOT / "testdata/ledger_report/v1.json"]}
        # Docker Desktop does not activate host-port publishing on an internal
        # bridge. Keep a dedicated ordinary bridge; database ports remain
        # unpublished, and only the app's loopback port is exposed.
        checked("network", "create", "--driver", "bridge", network)
        network_created = True
        progress("network_created")
        summary["state"] = "running"
        for provider_index, provider in enumerate(["postgres", "mysql"]):
            for mode_index, mode in enumerate(["enabled", "disabled"]):
                port = 18127 if mode == "enabled" else 18128
                round_name = provider + "-" + mode
                db_host = PREFIX + round_name + "-" + suffix
                progress("database_starting", provider=provider, mode=mode)
                dbname, user, password = "finexy_report_provider_" + secrets.token_hex(5), "report_" + secrets.token_hex(5), secrets.token_urlsafe(30)
                db_env = (["POSTGRES_DB=" + dbname, "POSTGRES_USER=" + user, "POSTGRES_PASSWORD=" + password]
                          if provider == "postgres" else ["MYSQL_DATABASE=" + dbname, "MYSQL_USER=" + user,
                          "MYSQL_PASSWORD=" + password, "MYSQL_ROOT_PASSWORD=" + secrets.token_urlsafe(30)])
                argv = ["run", "--rm", "-d", "--name", db_host, "--network", network]
                for item in db_env:
                    argv += ["-e", item]
                db_cid = checked(*argv, db_images[provider])
                owned.append(db_cid)
                inspect(db_cid)
                progress("database_readiness", provider=provider, mode=mode, databaseContainer=db_cid)
                for attempt in range(100):
                    ready = (docker("exec", db_cid, "pg_isready", "-h", "127.0.0.1", "-U", user, "-d", dbname) if provider == "postgres"
                             else docker("exec", "-e", "MYSQL_PWD=" + password, db_cid, "mysql", "--protocol=tcp", "-h127.0.0.1", "-u" + user, "-D", dbname, "-e", "SELECT 1"))
                    if ready.returncode == 0:
                        break
                    if attempt == 99:
                        raise RuntimeError("owned database readiness timed out")
                    time.sleep(0.5)
                app_name = "finexy-report-api-e2e-http-" + round_name + "-" + suffix
                app_env = {"EBK_DATABASE_TYPE": provider, "EBK_DATABASE_HOST": db_host + (":5432" if provider == "postgres" else ":3306"),
                           "EBK_DATABASE_NAME": dbname, "EBK_DATABASE_USER": user, "EBK_DATABASE_PASSWD": password,
                           "EBK_DATABASE_SSL_MODE": "disable", "EBK_DATABASE_AUTO_UPDATE_DATABASE": "true",
                           "EBK_DATABASE_LOG_QUERY": "false", "EBK_AUTH_ENABLE_REGISTER": "true", "EBK_SECURITY_ENABLE_API_TOKEN": "true",
                           "EBK_DATA_ENABLE_EXPORT": "true" if mode == "enabled" else "false",
                           "EBK_UUID_SERVER_ID": str(225 + provider_index * 2 + mode_index)}
                argv = ["run", "--rm", "-d", "--name", app_name, "--network", network, "-p", f"127.0.0.1:{port}:8080"]
                for key, value in app_env.items():
                    argv += ["-e", key + "=" + value]
                app_cid = checked(*argv, image_id)
                owned.append(app_cid)
                raw = inspect(app_cid, True, port)
                progress("application_readiness", provider=provider, mode=mode, databaseContainer=db_cid, applicationContainer=app_cid)
                effective = dict(entry.split("=", 1) for entry in raw["Config"]["Env"] if "=" in entry)
                assert all(effective.get(key) == value for key, value in app_env.items())
                binary = checked("exec", app_cid, "sha256sum", "/ezbookkeeping/ezbookkeeping").split()[0]
                assert binary == expected_binary
                for attempt in range(100):
                    live = docker("inspect", "--format", "{{.State.Running}}", app_cid)
                    if live.returncode or live.stdout.strip() != b"true":
                        raise RuntimeError("owned provider-backed application exited before health readiness")
                    if attempt % 5 == 0:
                        startup = docker("logs", "--tail", "50", app_cid).stdout.decode(errors="replace")
                        for key, value in effective.items():
                            if value and ("PASS" in key or "SECRET" in key or key == "EBK_DATABASE_USER"):
                                startup = startup.replace(value, "<redacted>")
                        (output / (round_name + "-startup.log")).write_text(startup, encoding="utf-8")
                    try:
                        with urllib.request.urlopen(f"http://127.0.0.1:{port}/healthz.json", timeout=2) as response:
                            if response.status == 200:
                                break
                    except (OSError, urllib.error.URLError):
                        pass
                    if attempt == 99:
                        raise RuntimeError("owned provider-backed application health timed out")
                    time.sleep(0.25)
                evidence = relative_output + "/" + round_name
                (ROOT / evidence).mkdir(parents=True, exist_ok=True)
                verifier_env = os.environ.copy()
                verifier_env.update(DOCKER_CLI=DOCKER, FINEXY_REPORT_CONTAINER=app_name,
                                    FINEXY_REPORT_URL=f"http://127.0.0.1:{port}", FINEXY_REPORT_IMAGE_ID=image_id,
                                    FINEXY_REPORT_EVIDENCE=evidence)
                argv = ["node", str(ROOT / ".github/scripts/verify-ledger-report.mjs")]
                if mode == "disabled":
                    argv.append("--export-disabled")
                progress("http_verifier", provider=provider, mode=mode, databaseContainer=db_cid, applicationContainer=app_cid)
                result = subprocess.run(argv, cwd=ROOT, env=verifier_env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                (output / (round_name + ".log")).write_bytes(result.stdout)
                round_summary = {"provider": provider, "mode": mode, "databaseImage": db_images[provider],
                                 "databaseContainer": db_cid, "applicationContainer": app_cid, "verifierExitCode": result.returncode,
                                 "binarySHA256": binary, "evidence": evidence}
                summary["rounds"].append(round_summary)
                if result.returncode == 0:
                    # Do not infer the backend solely from environment values:
                    # independently confirm the API-created synthetic records
                    # actually exist in this exact disposable provider schema.
                    quote = '"' if provider == "postgres" else "`"
                    sql = f'SELECT (SELECT COUNT(*) FROM {quote}user{quote}),(SELECT COUNT(*) FROM ledger),(SELECT COUNT(*) FROM {quote}transaction{quote})'
                    if provider == "postgres":
                        values = checked("exec", "-e", "PGPASSWORD=" + password, db_cid, "psql", "-h", "127.0.0.1", "-U", user, "-d", dbname, "-t", "-A", "-F", ",", "-c", sql)
                    else:
                        values = checked("exec", "-e", "MYSQL_PWD=" + password, db_cid, "mysql", "--protocol=tcp", "-h127.0.0.1", "-u" + user, "-D", dbname, "--batch", "--skip-column-names", "-e", sql)
                    counts = [int(value) for value in values.replace(",", " ").split()]
                    assert len(counts) == 3
                    round_summary["backendRowCounts"] = dict(zip(["users", "ledgers", "transactions"], counts))
                    assert counts[0] >= (5 if mode == "enabled" else 1)
                    if mode == "enabled":
                        assert counts[1] >= 2 and counts[2] > 0
                (output / (round_name + "-backend-summary.json")).write_text(json.dumps(round_summary, indent=2), encoding="utf-8")
                print(round_name + ": verifier exit " + str(result.returncode), flush=True)
                progress("round_complete", provider=provider, mode=mode, verifierExitCode=result.returncode)
                stop(app_cid)
                stop(db_cid)
                if result.returncode:
                    raise RuntimeError("provider-backed HTTP verifier failed; inspect safe verifier artifact")
        summary["state"] = "passed"
    except Exception as exc:
        summary["state"] = "failed"
        summary["errorType"] = type(exc).__name__
        summary["error"] = str(exc) if isinstance(exc, RuntimeError) else "isolated runner assertion failed"
        print("Provider HTTP verification did not pass; inspect summary artifact.", flush=True)
    finally:
        progress("cleanup", state=summary["state"])
        for cid in reversed(owned.copy()):
            stop(cid)
        network_result = docker("network", "rm", network).returncode if network_created else None
        summary["cleanup"] = {"containers": cleanup, "networkRemovalExitCode": network_result,
                              "notNeeded": not network_created and not cleanup}
        if summary["state"] == "passed" and (network_result != 0 or any(c["stopExitCode"] != 0 or not c["absent"] for c in cleanup)):
            summary["state"] = "failed"
        (output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        progress("finished", state=summary["state"])
    return 0 if summary["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
