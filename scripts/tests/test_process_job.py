"""Windows fixture trees must die even when their supervisor cannot run finally."""
import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import subprocess
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from windows.process_job import OwnedProcessJob


@unittest.skipUnless(os.name == 'nt', 'Windows process ownership')
class ProcessJobTests(unittest.TestCase):
    def test_closing_job_stops_attached_process(self):
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
        try:
            with OwnedProcessJob() as job:
                job.attach(child)
            self.assertIsNotNone(child.wait(timeout=10))
        finally:
            if child.poll() is None:
                child.kill()
            child.wait()

    def test_killing_supervisor_stops_fixture_descendant(self):
        source = str(Path(__file__).resolve().parents[1])
        code = '\n'.join([
            'import subprocess, sys, time',
            f'sys.path.insert(0, {source!r})',
            'from windows.process_job import OwnedProcessJob',
            'job = OwnedProcessJob()',
            'child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])',
            'job.attach(child)',
            'print(child.pid, flush=True)',
            'time.sleep(60)',
        ])
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = None
        supervisor = subprocess.Popen([sys.executable, '-u', '-c', code], stdout=subprocess.PIPE, text=True)
        try:
            pid = int(supervisor.stdout.readline())
            handle = kernel.OpenProcess(0x100000, False, pid)  # SYNCHRONIZE
            self.assertTrue(handle)
            supervisor.kill()
            supervisor.wait(timeout=10)
            self.assertEqual(0, kernel.WaitForSingleObject(handle, 10000))
        finally:
            if supervisor.poll() is None:
                supervisor.kill()
            supervisor.wait()
            supervisor.stdout.close()
            if handle:
                kernel.CloseHandle(handle)


if __name__ == '__main__':
    unittest.main()
