#!/usr/bin/env python3
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

"""Keep selected source worktrees leased until a composite build or its client exits."""

import argparse
import contextlib
import fcntl
import os
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, action='append', required=True)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command:
        parser.error('a command is required after --')
    with contextlib.ExitStack() as stack:
        # Stable ordering prevents two stands leasing the same source set in opposite order.
        for root in sorted({path.resolve() for path in args.root}):
            if not root.is_dir():
                parser.error(f'Source checkout does not exist: {root}')
            tools = root / '.toolchain'
            tools.mkdir(exist_ok=True)
            # Some nested source repos do not ignore this stand's generated lock metadata.
            # Ignore only these generated files and preserve any existing local rules.
            try:
                with (tools / '.gitignore').open('x') as ignore:
                    ignore.write('gradle-worktree.lock\n.gitignore\n')
            except FileExistsError:
                pass
            handle = stack.enter_context((tools / 'gradle-worktree.lock').open('a+b'))
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                print(f'Waiting for source worktree: {root}', file=sys.stderr, flush=True)
                fcntl.flock(handle, fcntl.LOCK_EX)
            os.set_inheritable(handle.fileno(), True)
        os.execvp(command[0], command)


if __name__ == '__main__':
    sys.exit(main())
