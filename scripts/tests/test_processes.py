"""Exercise command-tree ownership on success, failure, timeout and owner death."""
import ctypes
from ctypes import wintypes
import json
import os
import shlex
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import processes

SCRIPTS = Path(__file__).resolve().parents[1]
SLEEPER = 'import time; time.sleep(60)'


def stopped(pid, timeout=10):
    if os.name == 'nt':
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        kernel.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
        handle = kernel.OpenProcess(0x100001, False, pid)
        if not handle:
            error = ctypes.get_last_error()
            if error == 87:  # ERROR_INVALID_PARAMETER: PID no longer exists.
                return True
            raise ctypes.WinError(error)
        try:
            result = kernel.WaitForSingleObject(handle, int(timeout * 1000)) == 0
            if not result:
                kernel.TerminateProcess(handle, 1)  # Test owns this exact process.
            return result
        finally:
            kernel.CloseHandle(handle)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return True
        stat = Path(f'/proc/{pid}/stat')
        if stat.is_file() and stat.read_text().split(') ', 1)[1].startswith('Z'):
            return True
        time.sleep(.05)
    os.kill(pid, 9)
    return False


class ProcessTests(unittest.TestCase):
    def test_missing_command_preserves_original_error(self):
        with self.assertRaises(FileNotFoundError):
            processes.run(['lattice-command-not-installed.exe'])

    def test_job_attachment_failure_reaps_the_unattached_command(self):
        launched = []
        def fail(process):
            launched.append(process)
            raise OSError('job attachment failed')
        with patch.object(processes, 'OwnedProcessJob') as job:
            job.return_value.attach.side_effect = fail
            with self.assertRaisesRegex(OSError, 'job attachment failed'):
                processes.Popen([sys.executable, '-c', SLEEPER])
        self.assertTrue(stopped(launched[0].pid))

    def spawn_code(self, ending):
        return '\n'.join([
            'import subprocess, sys, time',
            f'child = subprocess.Popen([sys.executable, "-c", {SLEEPER!r}])',
            'print(child.pid, flush=True)',
            ending,
        ])

    def test_success_cleans_descendant_holding_output_pipe(self):
        result = processes.run([sys.executable, '-u', '-c', self.spawn_code('')],
                               capture_output=True, text=True, check=True, timeout=10)
        self.assertTrue(stopped(int(result.stdout.strip())))

    def test_failure_preserves_output_and_cleans_descendants(self):
        with self.assertRaises(subprocess.CalledProcessError) as failure:
            processes.check_output([sys.executable, '-u', '-c', self.spawn_code('raise SystemExit(7)')],
                                   text=True, timeout=10)
        self.assertEqual(7, failure.exception.returncode)
        self.assertTrue(stopped(int(failure.exception.stdout.strip())))

    def test_timeout_cleans_descendants(self):
        with self.assertRaises(subprocess.TimeoutExpired) as failure:
            processes.run([sys.executable, '-u', '-c', self.spawn_code('time.sleep(60)')],
                          capture_output=True, text=True, timeout=2)
        self.assertTrue(stopped(int(failure.exception.stdout.strip())))

    def test_timeout_cleans_shell_and_grandchildren(self):
        middle = '; '.join([
            'import json, os, subprocess, sys, time',
            f"child = subprocess.Popen([sys.executable, '-c', {SLEEPER!r}])",
            'print(json.dumps([os.getpid(), child.pid]), flush=True)',
            'time.sleep(60)',
        ])
        command = [sys.executable, '-u', '-c', middle]
        shell_command = [os.environ.get('COMSPEC', 'cmd.exe'), '/d', '/c', *command] if os.name == 'nt' else ['sh', '-c', shlex.join(command)]
        outer = '\n'.join([
            'import json, subprocess, time',
            f'shell = subprocess.Popen({shell_command!r}, stdout=subprocess.PIPE, text=True)',
            'descendants = json.loads(shell.stdout.readline())',
            'print(json.dumps([shell.pid, *descendants]), flush=True)',
            'time.sleep(60)',
        ])
        with self.assertRaises(subprocess.TimeoutExpired) as failure:
            processes.run([sys.executable, '-u', '-c', outer], capture_output=True, text=True, timeout=3)
        for pid in json.loads(failure.exception.stdout):
            self.assertTrue(stopped(pid), f'Shell descendant {pid} survived timeout')

    def test_killed_runner_cleans_descendants(self):
        code = '\n'.join([
            'import sys, time',
            f'sys.path.insert(0, {str(SCRIPTS)!r})',
            'import processes',
            f'child = processes.Popen([sys.executable, "-c", {SLEEPER!r}])',
            'print(child.pid, flush=True)',
            'time.sleep(60)',
        ])
        with subprocess.Popen([sys.executable, '-u', '-c', code], stdout=subprocess.PIPE, text=True) as owner:
            try:
                pid = int(owner.stdout.readline())
                owner.kill()
                owner.wait(timeout=10)
                self.assertTrue(stopped(pid))
            finally:
                if owner.poll() is None:
                    owner.kill()

    @unittest.skipUnless(os.name == 'nt', 'PowerShell lifecycle')
    def test_killed_powershell_parent_cleans_python_and_tools(self):
        with tempfile.TemporaryDirectory(prefix='lattice process parent ') as directory:
            root = Path(directory)
            script = root / 'fixture.py'
            ready = root / 'ready.json'
            script.write_text('\n'.join([
                'import json, os, sys, time',
                'from pathlib import Path',
                'import processes',
                f'child = processes.Popen([sys.executable, "-c", {SLEEPER!r}])',
                f'Path({str(ready)!r}).write_text(json.dumps([os.getpid(), child.pid]))',
                'time.sleep(60)',
            ]), encoding='utf-8')
            def quote(value):
                return "'" + str(value).replace("'", "''") + "'"
            command = (f'& {quote(sys.executable)} {quote(SCRIPTS / "processes.py")} '
                       f'--parent-pid $PID --script {quote(script)}')
            with subprocess.Popen(['powershell.exe', '-NoProfile', '-Command', command],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True) as shell:
                try:
                    deadline = time.monotonic() + 15
                    while not ready.is_file() and time.monotonic() < deadline and shell.poll() is None:
                        time.sleep(.05)
                    self.assertTrue(ready.is_file(), 'PowerShell fixture did not start')
                    pids = json.loads(ready.read_text())
                    shell.kill()
                    shell.wait(timeout=10)
                    for pid in pids:
                        self.assertTrue(stopped(pid), f'Process {pid} survived its PowerShell parent')
                finally:
                    if shell.poll() is None:
                        shell.kill()

    def test_explicit_application_handoff_survives_context_exit(self):
        child = processes.Popen([sys.executable, '-c', SLEEPER], owned=False)
        try:
            child.cleanup()
            self.assertIsNone(child.poll())
        finally:
            child.kill()
            child.wait(timeout=10)


if __name__ == '__main__':
    unittest.main()
