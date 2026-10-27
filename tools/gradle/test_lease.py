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

import fcntl
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class SourceLeaseTest(unittest.TestCase):
    def test_composite_waits_for_source_and_preserves_exit_status(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tools = root / '.toolchain'
            tools.mkdir()
            marker = root / 'executed'
            command = [sys.executable, str(Path(__file__).with_name('lease.py')), '--root', str(root), '--',
                       sys.executable, '-c', 'import pathlib,sys; pathlib.Path(sys.argv[1]).touch(); sys.exit(7)', str(marker)]
            with (tools / 'gradle-worktree.lock').open('a+b') as handle:
                fcntl.flock(handle, fcntl.LOCK_EX)
                process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                try:
                    self.assertIn('Waiting for source worktree:', process.stderr.readline())
                    self.assertFalse(marker.exists())
                    fcntl.flock(handle, fcntl.LOCK_UN)
                    process.communicate(timeout=10)
                    self.assertEqual(7, process.returncode)
                    self.assertTrue(marker.exists())
                finally:
                    if process.poll() is None:
                        process.kill()
                    process.communicate()

    def test_duplicate_source_paths_do_not_deadlock(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('lease.py')),
                                     '--root', directory, '--root', directory, '--', sys.executable, '-c', 'pass'],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_generated_lease_keeps_nested_git_checkout_clean(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('lease.py')),
                                     '--root', directory, '--', sys.executable, '-c', 'pass'],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            status = subprocess.check_output(['git', '-C', directory, 'status', '--porcelain'], text=True)
            self.assertEqual('', status)

    def test_preserves_existing_toolchain_ignore_rules(self):
        with tempfile.TemporaryDirectory() as directory:
            tools = Path(directory) / '.toolchain'
            tools.mkdir()
            ignore = tools / '.gitignore'
            ignore.write_text('custom-rule\n')
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('lease.py')),
                                     '--root', directory, '--', sys.executable, '-c', 'pass'],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('custom-rule\n', ignore.read_text())
