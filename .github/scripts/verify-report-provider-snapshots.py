#!/usr/bin/env python3
"""Run real PostgreSQL/MySQL report tests against disposable, no-volume DBs.

Only public DB images and an existing compiler image are used. Source is copied
into the compiler container; no repository or database volume is mounted. No
container publishes a host port. Disposable credentials remain process-local.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import subprocess
import tarfile
import time


ROOT = Path(__file__).resolve().parents[2]
DOCKER = os.environ.get("FINEXY_DOCKER", r"C:\Users\abc123\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe")
PREFIX = "finexy-report-provider-20261022-"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="artifacts/ledger-report-providers-20261022/provider-run")
    parser.add_argument("--builder", default="finexy-be-builder:mcp-receipt")
    parser.add_argument("--run", default="ExternalProvider")
    parser.add_argument("--full-checks", action="store_true", help="run full CGO Go suite and produce the static HTTP candidate")
    args = parser.parse_args()
    output = (ROOT / args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    suffix = secrets.token_hex(4)
    network = PREFIX + suffix
    containers = []
    summary = {"scope": "real isolated PostgreSQL and MySQL service/datastore tests", "run": args.run,
               "network": network, "providers": [], "containers": [], "complete": False,
               "notCovered": ["real deployment", "browser and Android clients", "other database versions/topologies", "production scale"]}

    def docker(*argv, **kwargs):
        return subprocess.run([DOCKER, *argv], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **kwargs)

    def checked(*argv, **kwargs):
        result = docker(*argv, **kwargs)
        if result.returncode:
            # Docker args may contain ephemeral passwords; never include argv.
            raise RuntimeError("Docker operation failed: " + result.stdout.decode(errors="replace")[-2000:])
        return result.stdout.decode(errors="replace").strip()

    def inspect(cid):
        raw = json.loads(checked("inspect", cid))[0]
        safe = {"id": raw["Id"], "name": raw["Name"], "image": raw["Image"], "mounts": raw["Mounts"],
                "autoRemove": raw["HostConfig"]["AutoRemove"], "portBindings": raw["HostConfig"]["PortBindings"]}
        if safe["mounts"] or safe["portBindings"]:
            raise RuntimeError("isolated container has unexpected mounts or published ports")
        summary["containers"].append(safe)
        return safe

    try:
        checked("network", "create", "--internal", network)
        # Freeze only source/config files required for Go tests. No data,
        # credentials, .env, log, storage, Android or build output is copied.
        source = io.BytesIO()
        manifest = {}
        with tarfile.open(fileobj=source, mode="w") as archive:
            files = ([ROOT / "go.mod", ROOT / "go.sum", Path(__file__).resolve()] + sorted(ROOT.glob("*.go"))
                     + sorted((ROOT / "pkg").rglob("*.go")) + sorted((ROOT / "cmd").rglob("*.go"))
                     + sorted(path for path in (ROOT / "pkg").rglob("*") if path.is_file() and "testdata" in path.relative_to(ROOT / "pkg").parts)
                     + sorted(path for directory in ["testdata", "templates"] for path in (ROOT / directory).rglob("*") if path.is_file()))
            files = sorted(set(files))
            for path in files:
                relative = path.relative_to(ROOT).as_posix()
                raw = path.read_bytes()
                manifest[relative] = hashlib.sha256(raw).hexdigest()
                info = tarfile.TarInfo(relative)
                info.size, info.mode = len(raw), 0o644
                archive.addfile(info, io.BytesIO(raw))
        (output / "source-candidate.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        summary["sourceManifestSHA256"] = hashlib.sha256((output / "source-candidate.json").read_bytes()).hexdigest()
        builder = checked("run", "--rm", "-d", "--name", PREFIX + "builder-" + suffix,
                          "--network", network, "-w", "/work", args.builder, "sleep", "7200")
        containers.append(builder)
        inspect(builder)
        checked("exec", builder, "mkdir", "-p", "/work")
        checked("exec", "-i", builder, "tar", "-xf", "-", "-C", "/work", input=source.getvalue())
        summary["compiler"] = checked("exec", builder, "go", "version")

        for provider, base, port in [("postgres", "postgres:17-alpine", 5432), ("mysql", "mysql:8.4", 3306)]:
            # COPY into a new scratch stage preserves the official rootfs and
            # ownership while dropping inherited VOLUME metadata. Data lives
            # only in this container's writable layer and vanishes with it.
            entry = "/usr/local/bin/docker-entrypoint.sh"
            extra = "ENV PGDATA=/var/lib/postgresql/data LANG=en_US.utf8\n" if provider == "postgres" else ""
            binary = "postgres" if provider == "postgres" else "mysqld"
            dockerfile = f'FROM {base} AS base\nFROM scratch\nCOPY --from=base / /\nENV PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n{extra}ENTRYPOINT ["{entry}"]\nCMD ["{binary}"]\n'
            image = PREFIX + "no-volume-" + provider + ":" + suffix
            build = docker("build", "-t", image, "-", input=dockerfile.encode())
            (output / f"{provider}-image-build.log").write_bytes(build.stdout)
            if build.returncode:
                raise RuntimeError(f"{provider} no-volume image build failed; see artifact")
            base_info = json.loads(checked("image", "inspect", base))[0]
            host, dbname = PREFIX + provider + "-" + suffix, "finexy_report_provider_" + suffix
            user, password = "report_" + secrets.token_hex(4), secrets.token_urlsafe(30)
            env = (["POSTGRES_DB=" + dbname, "POSTGRES_USER=" + user, "POSTGRES_PASSWORD=" + password]
                   if provider == "postgres" else ["MYSQL_DATABASE=" + dbname, "MYSQL_USER=" + user,
                   "MYSQL_PASSWORD=" + password, "MYSQL_ROOT_PASSWORD=" + secrets.token_urlsafe(30)])
            argv = ["run", "--rm", "-d", "--name", host, "--network", network]
            for item in env:
                argv += ["-e", item]
            cid = checked(*argv, image)
            containers.append(cid)
            inspect(cid)
            for attempt in range(100):
                probe = (docker("exec", cid, "pg_isready", "-U", user, "-d", dbname) if provider == "postgres"
                         else docker("exec", "-e", "MYSQL_PWD=" + password, cid, "mysql", "--protocol=tcp", "-h127.0.0.1", "-u" + user, "-D", dbname, "-e", "SELECT 1"))
                if probe.returncode == 0:
                    break
                if attempt == 99:
                    raise RuntimeError(provider + " did not become ready")
                time.sleep(1)
            version_cmd = (["exec", cid, "postgres", "--version"] if provider == "postgres" else ["exec", cid, "mysqld", "--version"])
            provider_info = {"provider": provider, "version": checked(*version_cmd), "baseImage": base_info["Id"],
                             "baseRepoDigests": base_info.get("RepoDigests"), "container": cid}
            summary["providers"].append(provider_info)
            argv = ["exec", "-e", "CGO_ENABLED=1", "-e", "GOTOOLCHAIN=local", "-e", "GOPROXY=off", "-e", "GOSUMDB=off"]
            for key, value in {"ISOLATED": "1", "TYPE": provider, "HOST": host + ":" + str(port),
                               "NAME": dbname, "USER": user, "PASSWORD": password}.items():
                argv += ["-e", "FINEXY_REPORT_PROVIDER_" + key + "=" + value]
            result = docker(*argv, builder, "go", "test", "-json", "-count=1", "-timeout=180s", "-run", args.run,
                            "./pkg/datastore", "./pkg/services")
            (output / (provider + "-tests.jsonl")).write_bytes(result.stdout)
            cases = []
            for line in result.stdout.splitlines():
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                if event.get("Test") and event.get("Action") in ("pass", "fail", "skip"):
                    cases.append({k: event.get(k) for k in ["Action", "Package", "Test", "Elapsed"]})
            # Run the actual complete production initializer only after report
            # fixtures finish; concurrent Sync2 calls would introduce unrelated
            # CREATE TABLE races between separate Go package test processes.
            schema = docker(*argv, builder, "go", "test", "-json", "-count=1", "-timeout=180s", "-run", "^TestApplicationSchema", "./cmd")
            (output / (provider + "-application-schema-tests.jsonl")).write_bytes(schema.stdout)
            for line in schema.stdout.splitlines():
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                if event.get("Test") and event.get("Action") in ("pass", "fail", "skip"):
                    cases.append({k: event.get(k) for k in ["Action", "Package", "Test", "Elapsed"]})
            provider_info.update(exitCode=result.returncode or schema.returncode, applicationSchemaExitCode=schema.returncode, cases=cases)
            print(provider + ": " + str(sum(c["Action"] == "pass" for c in cases)) + " passed / " + str(sum(c["Action"] == "fail" for c in cases)) + " failed / " + str(sum(c["Action"] == "skip" for c in cases)) + " skipped", flush=True)
        summary["complete"] = all(p["exitCode"] == 0 for p in summary["providers"]) and len(summary["providers"]) == 2
        if args.full_checks and summary["complete"]:
            full = docker("exec", "-e", "CGO_ENABLED=1", "-e", "GOTOOLCHAIN=local", "-e", "GOPROXY=off", "-e", "GOSUMDB=off",
                          builder, "go", "test", "-json", "-count=1", "-timeout=300s", "./...")
            (output / "go-test-all-cgo.jsonl").write_bytes(full.stdout)
            summary["fullCGOGoTestExitCode"] = full.returncode
            short_hash = summary["sourceManifestSHA256"][:12]
            ldflags = f'-s -w -linkmode external -extldflags -static -X main.Version=1.9.3 -X main.CommitHash={short_hash} -X main.BuildUnixTime={int(time.time())}'
            build = docker("exec", "-e", "CGO_ENABLED=1", "-e", "GOTOOLCHAIN=local", "-e", "GOPROXY=off", "-e", "GOSUMDB=off",
                           builder, "go", "build", "-trimpath", "-ldflags", ldflags, "-o", "/work/finexy", ".")
            (output / "go-build-static.log").write_bytes(build.stdout)
            summary["staticBuildExitCode"] = build.returncode
            if build.returncode == 0:
                checked("cp", builder + ":/work/finexy", str(output / "finexy"))
                summary["binarySHA256"] = hashlib.sha256((output / "finexy").read_bytes()).hexdigest()
                summary["candidateVersion"] = "1.9.3"
                summary["candidateCommitHash"] = short_hash
            summary["complete"] = summary["complete"] and full.returncode == 0 and build.returncode == 0
        elif args.full_checks:
            summary["fullChecksSkippedReason"] = "provider gate failed; repair the specific failure before the full suite/build"
    except Exception as exc:
        summary["error"] = str(exc)
        print("Provider verification failed; inspect summary artifact.", flush=True)
    finally:
        cleanup = []
        for cid in reversed(containers):
            stopped = docker("stop", "-t", "10", cid)
            exists = docker("inspect", cid)
            for attempt in range(20):
                if exists.returncode != 0:
                    break
                time.sleep(0.1)
                exists = docker("inspect", cid)
            cleanup.append({"id": cid, "stopExitCode": stopped.returncode, "absent": exists.returncode != 0})
        removed = docker("network", "rm", network)
        summary["cleanup"] = {"containers": cleanup, "networkRemovalExitCode": removed.returncode}
        summary["complete"] = summary["complete"] and removed.returncode == 0 and all(c["stopExitCode"] == 0 and c["absent"] for c in cleanup)
        (output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    return 0 if summary["complete"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
