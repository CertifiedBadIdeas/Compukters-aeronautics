#!/usr/bin/env python3
# The Compukters Developers
#
# Copyright 2026 The Compukters Developers
# Copyright 2026 Vsevolod Petrov (lazyhat)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Adapted from Orbitalis for the independent Orbitalis–Compukters stand and nested source repositories.
"""Allow three managed worktree builds, with one launch per checkout."""

import argparse
import fcntl
import os
from pathlib import Path
import subprocess
import sys
import time


from cache import main_checkout


def positive_setting(name):
    value = os.environ.get(name, "8")
    if not value.isdecimal() or int(value) < 1:
        raise ValueError(f"{name} must be a positive integer")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not args.command:
        parser.error("a build command is required")
    try:
        workers = positive_setting("FLIGHT_DEV_GRADLE_WORKERS")
        checkout = Path(subprocess.check_output(
            ["git", "rev-parse", "--show-toplevel"], cwd=args.root, text=True,
        ).strip()).resolve()
        workspace_tools = main_checkout(checkout) / ".toolchain"
        checkout_tools = checkout / ".toolchain"
        workspace_tools.mkdir(parents=True, exist_ok=True)
        checkout_tools.mkdir(parents=True, exist_ok=True)

        # A consumer and the main build mutate the same checkout outputs. Acquire this
        # before a workspace slot so a queued duplicate cannot occupy a second slot.
        checkout_lock = (checkout_tools / "gradle-worktree.lock").open("a+b")
        try:
            fcntl.flock(checkout_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("Waiting for this worktree's Gradle build...", file=sys.stderr, flush=True)
            fcntl.flock(checkout_lock, fcntl.LOCK_EX)

        # Old checkouts still take this lock exclusively. Shared ownership keeps new
        # launches compatible with them while the workspace transitions to three slots.
        legacy_lock = (workspace_tools / "gradle-build.lock").open("a+b")
        try:
            fcntl.flock(legacy_lock, fcntl.LOCK_SH | fcntl.LOCK_NB)
        except BlockingIOError:
            print("Waiting for a legacy workspace Gradle build...", file=sys.stderr, flush=True)
            fcntl.flock(legacy_lock, fcntl.LOCK_SH)

        slots = workspace_tools / "gradle-build-slots"
        slots.mkdir(exist_ok=True)
        waiting = False
        while True:
            slot = None
            for index in range(3):
                candidate = (slots / f"{index}.lock").open("a+b")
                try:
                    fcntl.flock(candidate, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    slot = candidate
                    break
                except BlockingIOError:
                    candidate.close()
            if slot is not None:
                break
            if not waiting:
                print("Waiting for one of three workspace Gradle build slots...", file=sys.stderr, flush=True)
                waiting = True
            time.sleep(0.1)

        # Keep every lease across exec and release it on actual build/client exit.
        for handle in (checkout_lock, legacy_lock, slot):
            os.set_inheritable(handle.fileno(), True)
        # Explicit command-line limits take precedence over wrapper defaults.
        supplied = args.command[1:]
        defaults = []
        if not any(arg == "--max-workers" or arg.startswith("--max-workers=") for arg in supplied):
            defaults.append(f"--max-workers={workers}")
        command = [args.command[0], *defaults, *supplied]
        os.execvp(command[0], command)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Cannot start managed Gradle build: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
