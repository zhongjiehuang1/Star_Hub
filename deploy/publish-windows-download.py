#!/usr/bin/env python3
"""Verify and attach the Windows preview to the Star Hub web application."""

import argparse
import ctypes
import fcntl
import grp
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile

ASSET = "StarHub-Windows-x64-Setup.exe"
MANIFEST = "starhub-windows.json"
CHECKSUM = ASSET + ".sha256"
FILES = (ASSET, CHECKSUM, MANIFEST)
PREVIEW_PATH = "windows/preview"


def digest(file):
    result = hashlib.sha256()
    with file.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            result.update(block)
    return result.hexdigest()


def metadata(source):
    manifest = json.loads((source / MANIFEST).read_text(encoding="utf-8-sig"))
    version = manifest["version"]
    if (not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version)
            or manifest["platform"] != "windows" or manifest["architecture"] != "x64"
            or manifest["asset"] != ASSET or manifest["bytes"] != (source / ASSET).stat().st_size
            or (source / CHECKSUM).read_text(encoding="ascii").split() != [manifest["sha256"], ASSET]
            or digest(source / ASSET) != manifest["sha256"]):
        raise SystemExit("Windows release files did not match the manifest.")
    return manifest


def create_directory(path, group):
    if path.is_symlink():
        raise SystemExit(f"Directory is a symlink: {path}")
    if not path.exists():
        path.mkdir(mode=0o750)
        os.chown(path, 0, group)
    if not path.is_dir():
        raise SystemExit(f"Expected a directory: {path}")


def atomic_write(target, data, group):
    fd, name = tempfile.mkstemp(prefix=".starhub-download-", dir=target.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            os.fchown(stream.fileno(), 0, group)
            os.fchmod(stream.fileno(), 0o640)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, target)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def exchange_directories(left, right):
    at_cwd, exchange = -100, 2  # Linux AT_FDCWD and RENAME_EXCHANGE.
    rename = ctypes.CDLL(None, use_errno=True).renameat2
    rename.argtypes = (ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint)
    rename.restype = ctypes.c_int
    if rename(at_cwd, os.fsencode(left), at_cwd, os.fsencode(right), exchange) != 0:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error), right)


def attach_preview(downloads, archived, release, group, expected_sha):
    target = downloads / PREVIEW_PATH
    create_directory(target.parent, group)
    if target.is_symlink():
        raise SystemExit("The Windows preview directory is a symlink.")
    replacing = target.exists()
    if replacing:
        if any((target / asset).is_symlink() for asset in FILES):
            raise SystemExit("The Windows preview contains a symlink.")
        current = metadata(target)
        if current == release:
            return
        if current["sha256"] != expected_sha:
            raise SystemExit("The current installer did not match --expect-preview-sha256.")

    with tempfile.TemporaryDirectory(prefix=".incoming-", dir=target.parent) as temp:
        staging = Path(temp) / "preview"
        create_directory(staging, group)
        for asset in FILES:
            os.link(archived / asset, staging / asset)
        if replacing:
            exchange_directories(staging, target)
        else:
            staging.rename(target)


def publish(args):
    if os.geteuid() != 0:
        raise SystemExit("Run as the Star Hub deployment owner (root).")
    group = grp.getgrnam("tomcat").gr_gid
    create_directory(args.archive, group)
    with (args.archive / "publish.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        publish_locked(args, group)


def publish_locked(args, group):
    release = metadata(args.assets)
    version = release["version"]
    downloads = args.webapp / "downloads"
    create_directory(downloads, group)

    page = old_page = old_page_hash = None
    landing = downloads / "index.html"
    if args.page:
        page = args.page.read_bytes()
        expected_links = [f'href="{PREVIEW_PATH}/{asset}"'.encode("ascii") for asset in (ASSET, CHECKSUM)]
        if any(page.count(link) != 2 for link in expected_links) or landing.is_symlink():
            raise SystemExit("The page must link to the verified Windows preview and its checksum.")
        old_page = landing.read_bytes()
        old_page_hash = hashlib.sha256(old_page).hexdigest()
        if page != old_page and old_page_hash != args.expect_page_sha256:
            raise SystemExit("The current download page changed. Check the live contents.")

    archived = args.archive / version
    if archived.is_symlink():
        raise SystemExit("The release archive path is a symlink.")
    if not archived.exists():
        staging = Path(tempfile.mkdtemp(prefix=".incoming-", dir=args.archive))
        try:
            os.chown(staging, 0, group)
            staging.chmod(0o750)
            for asset in FILES:
                target = staging / asset
                shutil.copyfile(args.assets / asset, target)
                os.chown(target, 0, group)
                target.chmod(0o640)
            if metadata(staging) != release:
                raise SystemExit("Archived release verification failed.")
            staging.rename(archived)
        finally:
            if staging.exists():
                shutil.rmtree(staging)
    elif metadata(archived) != release:
        raise SystemExit("An archive for this version already contains different files.")

    attach_preview(downloads, archived, release, group, args.expect_preview_sha256)
    if page is not None and page != old_page:
        if landing.is_symlink() or digest(landing) != old_page_hash:
            raise SystemExit("The current download page changed. Check the live contents.")
        preimage = args.archive / f"page-before-{old_page_hash}.html"
        if not preimage.exists():
            atomic_write(preimage, old_page, group)
        atomic_write(landing, page, group)
    print(f"Star Hub Windows preview: {release['bytes']} bytes, SHA-256 {release['sha256']}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--webapp", type=Path, default=Path("/var/lib/codex-validation/tomcat-atomic/webapps/starhub"))
    parser.add_argument("--archive", type=Path, default=Path("/var/lib/codex-validation/starhub-windows-releases"))
    parser.add_argument("--page", type=Path)
    parser.add_argument("--expect-page-sha256")
    parser.add_argument("--expect-preview-sha256", help="Current installer digest when changing the preview")
    publish(parser.parse_args())
