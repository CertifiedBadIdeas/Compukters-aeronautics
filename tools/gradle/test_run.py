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
import fcntl
import json
import os
import select
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

RUNNER = Path(__file__).with_name('run.py').resolve()


class BuildGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'workspace'
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        subprocess.run(['git', '-C', str(self.root), '-c', 'user.name=Test', '-c',
                        'user.email=test@example.com', 'commit', '-qm', 'init', '--allow-empty'], check=True)
        self.linked = Path(self.temp.name) / 'linked'
        subprocess.run(['git', '-C', str(self.root), 'worktree', 'add', '-qb', 'task', str(self.linked)],
                       check=True, stdout=subprocess.DEVNULL)
        self.probe = Path(self.temp.name) / 'probe'
        self.probe.write_text('''#!/usr/bin/env python3
import json,os,sys,time
from pathlib import Path
log=Path(sys.argv[-2]); label=sys.argv[-1]
with log.open('a') as f: f.write(json.dumps([label,'start',sys.argv[1:]])+'\\n')
if os.environ.get('BUILD_GATE_HOLD'):
    deadline=time.monotonic()+15
    while not (log.parent/(label+'.release')).exists():
        if time.monotonic()>deadline: sys.exit(99)
        time.sleep(0.01)
with log.open('a') as f: f.write(json.dumps([label,'end'])+'\\n')
''')
        self.probe.chmod(0o755)
        self.log = Path(self.temp.name) / 'events'
        self.env = dict(os.environ, FLIGHT_DEV_GRADLE_WORKERS='8')

    def launch(self, root, label, *args, hold=False):
        env = dict(self.env)
        if hold:
            env['BUILD_GATE_HOLD'] = '1'
        process = subprocess.Popen(
            [sys.executable, str(RUNNER), '--root', str(root), str(self.probe),
             *args, str(self.log), label], env=env,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        self.addCleanup(self.stop, process)
        return process

    @staticmethod
    def stop(process):
        if process.poll() is None:
            process.kill()
        process.communicate(timeout=5)

    def rows(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def wait_for_start(self, label):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if any(row[:2] == [label, 'start'] for row in self.rows()):
                return
            time.sleep(0.01)
        self.fail(f'{label} did not start: {self.rows()}')

    def wait_for_queue(self, process):
        ready, _, _ = select.select([process.stderr], [], [], 5)
        self.assertTrue(ready, 'Build never reported queueing')
        self.assertIn('Waiting', process.stderr.readline())

    def release(self, label):
        (Path(self.temp.name) / (label + '.release')).touch()

    def finish(self, process):
        _, stderr = process.communicate(timeout=10)
        self.assertEqual(process.returncode, 0, stderr)

    def test_three_distinct_worktrees_run_and_fourth_waits(self):
        roots = [self.root, self.linked]
        for index in range(2):
            linked = Path(self.temp.name) / f'linked-{index}'
            subprocess.run(['git', '-C', str(self.root), 'worktree', 'add', '-qb',
                            f'task-{index}', str(linked)], check=True, stdout=subprocess.DEVNULL)
            roots.append(linked)
        processes = []
        for index, root in enumerate(roots[:3]):
            label = str(index)
            args = ['--max-workers=2'] if index == 1 else []
            processes.append(self.launch(root, label, *args, hold=True))
            self.wait_for_start(label)
        fourth = self.launch(roots[3], 'fourth', hold=True)
        self.wait_for_queue(fourth)
        self.assertFalse(any(row[0] == 'fourth' for row in self.rows()))
        self.release('0')
        self.finish(processes[0])
        self.wait_for_start('fourth')
        for label in ('1', '2', 'fourth'):
            self.release(label)
        for process in [*processes[1:], fourth]:
            self.finish(process)
        starts = {row[0]: row[2] for row in self.rows() if row[1] == 'start'}
        self.assertEqual(starts['0'][:1], ['--max-workers=8'])
        self.assertEqual(starts['1'][:1], ['--max-workers=2'])

    def test_nested_consumer_waits_for_same_worktree_only(self):
        nested = self.root / 'consumers/flight'
        nested.mkdir(parents=True)
        first = self.launch(self.root, 'first', hold=True)
        self.wait_for_start('first')
        duplicate = self.launch(nested, 'duplicate', hold=True)
        self.wait_for_queue(duplicate)
        independent = self.launch(self.linked, 'independent', hold=True)
        self.wait_for_start('independent')
        self.assertFalse(any(row[0] == 'duplicate' for row in self.rows()))
        self.release('first')
        self.finish(first)
        self.wait_for_start('duplicate')
        for label in ('duplicate', 'independent'):
            self.release(label)
        self.finish(duplicate)
        self.finish(independent)

    def test_legacy_exclusive_build_blocks_new_slots(self):
        lock = self.root / '.toolchain/gradle-build.lock'
        lock.parent.mkdir()
        with lock.open('a+b') as handle:
            fcntl.flock(handle, fcntl.LOCK_EX)
            process = self.launch(self.linked, 'new', hold=True)
            self.wait_for_queue(process)
            self.assertEqual(self.rows(), [])
        self.wait_for_start('new')
        self.release('new')
        self.finish(process)

    def test_failure_releases_slot_and_preserves_status(self):
        failure = Path(self.temp.name) / 'failure'
        failure.write_text('#!/bin/sh\nexit 7\n')
        failure.chmod(0o755)
        result = subprocess.run([sys.executable, str(RUNNER), '--root', str(self.root), str(failure)],
                                env=self.env, capture_output=True)
        self.assertEqual(result.returncode, 7)
        process = self.launch(self.linked, 'after')
        process.communicate(timeout=10)
        self.assertEqual(process.returncode, 0)

    def test_invalid_limit_does_not_start_build(self):
        self.env['FLIGHT_DEV_GRADLE_WORKERS'] = '0'
        process = self.launch(self.root, 'invalid')
        _, stderr = process.communicate(timeout=10)
        self.assertEqual(process.returncode, 1)
        self.assertIn('positive integer', stderr)
        self.assertFalse(self.log.exists())


if __name__ == '__main__':
    unittest.main()
