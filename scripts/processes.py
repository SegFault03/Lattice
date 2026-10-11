"""Own temporary command trees; keep intentional application handoffs explicit."""
import argparse
import os
from pathlib import Path
import runpy
import signal
import subprocess as _subprocess
import sys
import threading

from windows.process_job import OwnedProcessJob


def __getattr__(name):
    return getattr(_subprocess, name)


def _kill_group(pid):
    try:
        os.killpg(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


class Popen(_subprocess.Popen):
    """Every temporary command owns a Windows job or a POSIX process group."""
    def __init__(self, *args, owned=True, **kwargs):
        self._owned = owned
        self._cleanup_lock = threading.Lock()
        self._cleaned = False
        self._job = OwnedProcessJob() if owned else None
        self._guardian = None
        self._lifeline = None
        self._watcher = None
        if owned and os.name != 'nt':
            if kwargs.get('start_new_session') is False or kwargs.get('process_group') is not None:
                raise ValueError('Owned commands require a new process session')
            kwargs['start_new_session'] = True
        try:
            super().__init__(*args, **kwargs)
            if owned:
                self._job.attach(self)
                if os.name != 'nt':
                    # The guardian survives a SIGKILL of this runner. Only the
                    # runner holds the write end; commands cannot inherit it.
                    read_fd, self._lifeline = os.pipe()
                    try:
                        self._guardian = _subprocess.Popen(
                            [sys.executable, str(Path(__file__).resolve()), '--guard', str(read_fd), str(self.pid)],
                            pass_fds=(read_fd,), start_new_session=True,
                            stdin=_subprocess.DEVNULL, stdout=_subprocess.DEVNULL, stderr=_subprocess.DEVNULL)
                    finally:
                        os.close(read_fd)
                self._watcher = threading.Thread(target=self._watch_exit, daemon=True)
                self._watcher.start()
        except BaseException:
            if getattr(self, '_child_created', False):
                self.kill()
                _subprocess.Popen.wait(self)
            elif self._job:
                self._job.close()
            raise

    def _watch_exit(self):
        _subprocess.Popen.wait(self)
        # A finished shell can leave descendants holding its output pipes open.
        self.cleanup()

    def cleanup(self):
        if not self._owned:
            return
        with self._cleanup_lock:
            if self._cleaned:
                return
            self._cleaned = True
            if os.name == 'nt':
                self._job.close()
            else:
                _kill_group(self.pid)
            if self._lifeline is not None:
                try:
                    os.write(self._lifeline, b'q')  # Normal cleanup: guardian may exit.
                except BrokenPipeError:
                    pass
                os.close(self._lifeline)
                self._lifeline = None
            if self._guardian:
                try:
                    self._guardian.wait(timeout=10)
                except _subprocess.TimeoutExpired:
                    self._guardian.kill()
                    self._guardian.wait()

    def kill(self):
        if self._owned:
            self.cleanup()
            # Also covers failure to attach a newly created process to its job.
            if self.poll() is None:
                super().kill()
        else:
            super().kill()

    def terminate(self):
        if self._owned:
            self.kill()
        else:
            super().terminate()

    def wait(self, timeout=None):
        code = super().wait(timeout=timeout)
        self.cleanup()
        return code

    def __exit__(self, *exc):
        try:
            self.cleanup()
            return super().__exit__(*exc)
        finally:
            if self._watcher and self._watcher is not threading.current_thread():
                self._watcher.join(timeout=10)


def run(*args, input=None, capture_output=False, timeout=None, check=False, **kwargs):
    if input is not None:
        if kwargs.get('stdin') is not None:
            raise ValueError('stdin and input arguments may not both be used')
        kwargs['stdin'] = _subprocess.PIPE
    if capture_output:
        if kwargs.get('stdout') is not None or kwargs.get('stderr') is not None:
            raise ValueError('stdout/stderr cannot be combined with capture_output')
        kwargs.update(stdout=_subprocess.PIPE, stderr=_subprocess.PIPE)
    with Popen(*args, **kwargs) as process:
        try:
            stdout, stderr = process.communicate(input, timeout=timeout)
        except _subprocess.TimeoutExpired as error:
            process.kill()
            error.stdout, error.stderr = process.communicate()
            raise
        except BaseException:
            process.kill()
            process.wait()
            raise
        code = process.poll()
        if check and code:
            raise _subprocess.CalledProcessError(code, process.args, output=stdout, stderr=stderr)
        return _subprocess.CompletedProcess(process.args, code, stdout, stderr)


def check_output(*args, timeout=None, **kwargs):
    if 'stdout' in kwargs or 'check' in kwargs:
        raise ValueError('stdout and check cannot be supplied to check_output')
    return run(*args, stdout=_subprocess.PIPE, timeout=timeout, check=True, **kwargs).stdout


def check_call(*args, **kwargs):
    return run(*args, check=True, **kwargs).returncode


def _watch_parent(pid):
    if os.name == 'nt':
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        parent = kernel.OpenProcess(0x100000, False, pid)
        if not parent:
            raise ctypes.WinError(ctypes.get_last_error())
        def monitor():
            kernel.WaitForSingleObject(parent, 0xFFFFFFFF)
            kernel.CloseHandle(parent)
            # OS closure of our job handles stops every owned command tree.
            os._exit(130)
        threading.Thread(target=monitor, daemon=True).start()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parent-pid', type=int)
    parser.add_argument('--script', type=Path)
    parser.add_argument('--guard', nargs=2, type=int, help=argparse.SUPPRESS)
    parser.add_argument('--command', action='store_true')
    parser.add_argument('arguments', nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    if args.guard:
        fd, pid = args.guard
        try:
            if os.read(fd, 1) != b'q':
                _kill_group(pid)
        finally:
            os.close(fd)
        return 0
    def interrupt(signum, frame):
        raise KeyboardInterrupt
    for name in ('SIGTERM', 'SIGHUP', 'SIGBREAK'):
        if hasattr(signal, name):
            signal.signal(getattr(signal, name), interrupt)
    if args.parent_pid:
        _watch_parent(args.parent_pid)
    arguments = args.arguments[1:] if args.arguments[:1] == ['--'] else args.arguments
    try:
        if args.script:
            script = args.script.resolve()
            sys.path.insert(0, str(script.parent))
            sys.argv = [str(script), *arguments]
            runpy.run_path(str(script), run_name='__main__')
            return 0
        if args.command and arguments:
            return run(arguments).returncode
        parser.error('Provide --script PATH or --command followed by a command')
    except KeyboardInterrupt:
        return 130


if __name__ == '__main__':
    raise SystemExit(main())
