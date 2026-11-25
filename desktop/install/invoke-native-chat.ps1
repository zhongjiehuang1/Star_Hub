param([Int64]$WindowHandle, [switch]$TestDevice,
      [string]$Prompt = 'Show the native test reply.', [switch]$TestCloseWarning)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
$window = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]$WindowHandle)

function Find-Element($property, $value) {
    $condition = [System.Windows.Automation.PropertyCondition]::new($property, $value)
    for ($i = 0; $i -lt 20; $i++) {
        $match = $window.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $condition)
        if ($match) { return $match }
        Start-Sleep -Milliseconds 250
    }
    throw "Native control is missing: $value"
}

function Find-Control($automationId) {
    Find-Element ([System.Windows.Automation.AutomationElement]::AutomationIdProperty) $automationId
}

function Invoke-Control($control) {
    $control.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
}

if ($TestDevice) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class WindowLayout
{
    [DllImport("user32.dll")]
    public static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int width, int height, uint flags);
}
'@
    $handle = [IntPtr]$WindowHandle
    $original = $window.Current.BoundingRectangle
    try {
        if (![WindowLayout]::SetWindowPos($handle, [IntPtr]::Zero, 0, 0, 620, 520, 0x16)) {
            throw 'Could not resize the native window for verification.'
        }
        $heading = Find-Control 'WelcomeHeading'
        $viewport = Find-Control 'Transcript'
        $sendButton = Find-Control 'Send'
        $aligned = $false
        for ($layout = 0; $layout -lt 20; $layout++) {
            $title = $heading.Current.BoundingRectangle
            $chat = $viewport.Current.BoundingRectangle
            $sendBounds = $sendButton.Current.BoundingRectangle
            $horizontal = [Math]::Abs(($title.Left + $title.Right - $chat.Left - $chat.Right) / 2)
            $vertical = [Math]::Abs(($title.Top + $title.Bottom - $chat.Top - $chat.Bottom) / 2)
            if ($chat.Width -gt 0 -and $chat.Width -le 620 -and $horizontal -le 4 -and $vertical -le 4 -and
                $sendBounds.Width -gt 0 -and $sendBounds.Right -le $chat.Right) {
                $aligned = $true
                break
            }
            Start-Sleep -Milliseconds 250
        }
        if (!$aligned) { throw "The narrow chat layout did not fit. Viewport: $chat; heading: $title; send: $sendBounds." }
        Write-Host 'The empty state is centered and the send button remains visible at a narrow width.'
    }
    finally {
        [void][WindowLayout]::SetWindowPos($handle, [IntPtr]::Zero, 0, 0, [int]$original.Width, [int]$original.Height, 0x16)
    }
    Invoke-Control (Find-Control 'DeviceMonitor')
    $sample = Find-Control 'DeviceSampleTime'
    $memory = Find-Control 'DeviceMemory'
    $cpu = Find-Control 'DeviceCPU'
    $first = $null
    $refreshed = $false
    for ($i = 0; $i -lt 36; $i++) {
        $current = $sample.Current.Name
        if ($current -like 'Updated at *') {
            if (!$first) { $first = $current }
            if ($first -ne $current -and $memory.Current.Name -match 'Memory \d+%' -and $cpu.Current.Name -match 'CPU \d+%') {
                $refreshed = $true
                break
            }
        }
        Start-Sleep -Milliseconds 250
    }
    if (!$refreshed) { throw "The device monitor did not refresh Windows CPU and memory readings. $($sample.Current.Name) / $($cpu.Current.Name) / $($memory.Current.Name)" }
    Write-Host 'The device monitor displayed live readings from the background worker.'
    $close = Find-Element ([System.Windows.Automation.AutomationElement]::NameProperty) 'Close device monitor'
    Invoke-Control $close
}

$composer = Find-Control 'Composer'
$setText = $composer.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
$prompt = if ($TestDevice) { 'Why is my computer slow?' } else { $Prompt }
$setText.SetValue($prompt)
if ($TestCloseWarning) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class CloseWithDraft
{
    [DllImport("user32.dll")]
    public static extern bool PostMessage(IntPtr hwnd, uint message, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")]
    public static extern IntPtr GetLastActivePopup(IntPtr hwnd);
    [DllImport("user32.dll")]
    public static extern IntPtr GetDlgItem(IntPtr dialog, int controlId);
    [DllImport("user32.dll")]
    public static extern bool IsWindow(IntPtr hwnd);
}
'@
    $handle = [IntPtr]$WindowHandle
    if (![CloseWithDraft]::PostMessage($handle, 0x10, [IntPtr]::Zero, [IntPtr]::Zero)) {
        throw 'Could not send the Windows close message.'
    }
    $noButton = [IntPtr]::Zero
    for ($i = 0; $i -lt 40; $i++) {
        $popup = [CloseWithDraft]::GetLastActivePopup($handle)
        if ($popup -ne [IntPtr]::Zero -and $popup -ne $handle) {
            $noButton = [CloseWithDraft]::GetDlgItem($popup, 7)
            if ($noButton -ne [IntPtr]::Zero) { break }
        }
        Start-Sleep -Milliseconds 250
    }
    if ($noButton -eq [IntPtr]::Zero) { throw 'Star Hub did not ask before closing with an unsent draft.' }
    if (![CloseWithDraft]::PostMessage($popup, 0x111, [IntPtr]7, $noButton)) {
        throw 'Could not cancel the Windows close warning.'
    }
    for ($i = 0; [CloseWithDraft]::IsWindow($popup) -and $i -lt 20; $i++) { Start-Sleep -Milliseconds 100 }
    if ([CloseWithDraft]::IsWindow($popup)) { throw 'The close warning did not dismiss when cancelled.' }
    if ($setText.Current.Value -ne $prompt) { throw 'Cancelling close did not preserve the draft.' }
    Write-Host 'The native close warning preserved the unsent draft when cancelled.'
}
$send = Find-Control 'Send'
for ($i = 0; !$send.Current.IsEnabled -and $i -lt 20; $i++) { Start-Sleep -Milliseconds 100 }
Invoke-Control $send
