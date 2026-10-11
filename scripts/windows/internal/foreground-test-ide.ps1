param([datetime]$StartedAfter)

$ErrorActionPreference = 'Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class LatticeTestIdeForeground {
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window, out uint process);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] static extern bool AttachThreadInput(uint from, uint to, bool attach);
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")] static extern bool IsIconic(IntPtr window);
    [DllImport("user32.dll")] static extern bool ShowWindowAsync(IntPtr window, int command);

    public static void Activate(IntPtr window, uint process) {
        uint foregroundProcess;
        uint foregroundThread = GetWindowThreadProcessId(GetForegroundWindow(), out foregroundProcess);
        // A test IDE dialog or popup already owns the foreground. Do not close it
        // by activating the main frame on every polling pass.
        if (foregroundProcess == process) return;
        uint ignored;
        uint targetThread = GetWindowThreadProcessId(window, out ignored);
        uint currentThread = GetCurrentThreadId();
        bool foregroundAttached = foregroundThread != 0 && foregroundThread != currentThread
            && AttachThreadInput(currentThread, foregroundThread, true);
        bool targetAttached = targetThread != 0 && targetThread != currentThread && targetThread != foregroundThread
            && AttachThreadInput(currentThread, targetThread, true);
        try {
            if (IsIconic(window)) ShowWindowAsync(window, 9); // SW_RESTORE
            SetForegroundWindow(window);
        } finally {
            if (targetAttached) AttachThreadInput(currentThread, targetThread, false);
            if (foregroundAttached) AttachThreadInput(currentThread, foregroundThread, false);
        }
    }
}
'@

while ($true) {
    # This project name belongs to IntellijUiScreenshotTest. Restrict recovery to
    # a newly launched process so an existing user IDE is never activated.
    $testIde = Get-Process java, idea64 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.MainWindowHandle -ne 0 -and $_.StartTime -ge $StartedAfter -and
            ($_.MainWindowTitle -like '*lattice-ui-test*' -or $_.MainWindowTitle -like '*ui-test-project*')
        } |
        Sort-Object StartTime -Descending | Select-Object -First 1
    if ($testIde) { [LatticeTestIdeForeground]::Activate($testIde.MainWindowHandle, $testIde.Id) }
    Start-Sleep -Milliseconds 500
}
