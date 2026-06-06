# wt-setup.ps1 -- Emulin: make emacs/vim key combos reach the app under
# Windows Terminal.
#
# Windows Terminal reads a single fixed settings.json and has NO command-line
# way to pass keybindings, so this script edits that file (backing it up
# first). WT hot-reloads settings.json on save, so changes take effect at once.
# Opt out with EMULIN_NO_WT_SETUP=1 (checked by the caller). Invoked by
# emulin.bat.
#
# Two fixes are applied, both idempotent:
#   1. Ctrl+V  : WT binds it to "paste" by default and consumes it, so emacs
#                C-v (scroll-up) / vim CTRL-V (visual-block) never arrive. We
#                add an "unbound" entry so the keystroke passes through.
#   2. Ctrl+Space : emacs C-SPC (set-mark-command) expects the NUL byte
#                (0x00 = C-@), but WT sends NOTHING for Ctrl+Space by default,
#                so region selection / copy-paste mark is unusable. We add a
#                "sendInput" entry that emits NUL on Ctrl+Space.  (issue #216)
#
# Safe by design:
#   - text insertion only (never re-serializes the JSON; comments preserved)
#   - skips a key if a binding for it already exists (idempotent; respects the
#     user's own bindings)
#   - on any unrecognized structure / error, leaves the file untouched and
#     prints the manual fragment path.
#
# -SettingsPath overrides auto-detection (used by the test harness).

param([string]$SettingsPath)

$ErrorActionPreference = 'Stop'
$fragPath = if ($PSScriptRoot) { Join-Path $PSScriptRoot 'windows-terminal-settings.jsonc' } else { $null }

function Show-Manual($why) {
    Write-Host "[emulin] $why"
    Write-Host '[emulin] To enable the emacs/vim keys manually, open Windows Terminal'
    Write-Host '[emulin] settings (Ctrl+,) and add to its "keybindings"/"actions" array:'
    Write-Host '[emulin]     { "command": "unbound", "keys": "ctrl+v" }'
    Write-Host '[emulin]     { "command": { "action": "sendInput", "input": "<NUL>" }, "keys": "ctrl+space" }'
    Write-Host '[emulin]     (<NUL> = the JSON escape for U+0000, i.e. backslash-u-0000)'
    if ($fragPath -and (Test-Path $fragPath)) { Write-Host "[emulin] Full fragment: $fragPath" }
}

# 1. Locate settings.json: Store/stable + Preview packages, then unpackaged.
if ($SettingsPath) {
    $settings = $SettingsPath
} else {
    $candidates = @()
    $pkgs = Join-Path $env:LOCALAPPDATA 'Packages'
    if (Test-Path $pkgs) {
        Get-ChildItem -Path $pkgs -Directory -Filter 'Microsoft.WindowsTerminal*' -ErrorAction SilentlyContinue |
            ForEach-Object { $candidates += (Join-Path $_.FullName 'LocalState\settings.json') }
    }
    $candidates += (Join-Path $env:LOCALAPPDATA 'Microsoft\Windows Terminal\settings.json')
    $settings = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $settings -or -not (Test-Path $settings)) {
    Show-Manual 'Windows Terminal settings.json not found (launch WT once to create it).'
    exit 0
}

# 2. Read raw text (preserve exactly) and detect the newline style.
$text = Get-Content -LiteralPath $settings -Raw
$nl = if ($text -match "`r`n") { "`r`n" } else { "`n" }

# Helper: insert an entry as the first element of the "keybindings" (preferred,
# new 1.19+ schema) or "actions" (legacy) array. The new schema is id-based and
# the legacy one is command-based, so the caller passes a form for each.
# Returns the modified text, or $null if neither array exists. The entries must
# not contain a literal '$'.
function Add-Entry([string]$txt, [string]$entryKb, [string]$entryAc) {
    $rxKb = [regex]'(?is)("keybindings"\s*:\s*\[)'
    $rxAc = [regex]'(?is)("actions"\s*:\s*\[)'
    if ($rxKb.IsMatch($txt)) {
        return $rxKb.Replace($txt, ('${1}' + $nl + '        ' + $entryKb + ','), 1)
    } elseif ($rxAc.IsMatch($txt)) {
        return $rxAc.Replace($txt, ('${1}' + $nl + '        ' + $entryAc + ','), 1)
    }
    return $null
}

$new = $text
$noArray = $false

# 3. Ctrl+V: ensure it is unbound so the keystroke passes through to the app.
#    WT's default paste binding lives in a hidden defaults.json; a user-level
#    "unbound" entry overrides it. An existing ctrl+v entry has no nested
#    braces, so the {...} regex is safe here.
$rxV = [regex]'\{[^{}]*"keys"\s*:\s*"ctrl\+v"[^{}]*\}'
$mV = $rxV.Match($new)
if ($mV.Success) {
    if ($mV.Value -notmatch '(?i)unbound') {
        if ($mV.Value -match '"id"\s*:') {
            $entryV = '{ "id": "unbound", "keys": "ctrl+v" }'
        } else {
            $entryV = '{ "command": "unbound", "keys": "ctrl+v" }'
        }
        $new = $new.Substring(0, $mV.Index) + $entryV + $new.Substring($mV.Index + $mV.Length)
    }
} else {
    $added = Add-Entry $new '{ "id": "unbound", "keys": "ctrl+v" }' '{ "command": "unbound", "keys": "ctrl+v" }'
    if ($null -ne $added) { $new = $added } else { $noArray = $true }
}

# 4. Ctrl+Space: add a sendInput binding that emits NUL (U+0000) so emacs
#    C-SPC (set-mark-command) works. Only ADD when absent -- never clobber the
#    user's own ctrl+space binding (idempotent). Our own entry has nested
#    braces, so detect presence by the "keys":"ctrl+space" marker instead of a
#    whole-entry {...} regex.  (issue #216)
$rxSpace = [regex]'(?i)"keys"\s*:\s*"ctrl\+space"'
if (-not $rxSpace.IsMatch($new)) {
    # Build the JSON escape for U+0000 at runtime: backslash (char 92) + u0000.
    # (Written this way so the source file never holds a literal NUL.)
    $nulEsc = "$([char]92)u0000"
    $entrySpace = '{ "command": { "action": "sendInput", "input": "' + $nulEsc + '" }, "keys": "ctrl+space" }'
    $added = Add-Entry $new $entrySpace $entrySpace
    if ($null -ne $added) { $new = $added } else { $noArray = $true }
}

# 5. Write back if anything changed (back up once).
if ($new -eq $text) {
    if ($noArray) {
        Show-Manual 'No "keybindings"/"actions" array in settings.json; add the entries manually.'
    } else {
        Write-Host '[emulin] Windows Terminal: Ctrl+V / Ctrl+Space already configured for emacs/vim.'
    }
    exit 0
}
try {
    $bak = "$settings.emulin-bak"
    if (-not (Test-Path $bak)) { Copy-Item -LiteralPath $settings -Destination $bak -Force }
    [System.IO.File]::WriteAllText($settings, $new, (New-Object System.Text.UTF8Encoding $false))
    Write-Host '[emulin] Windows Terminal: enabled Ctrl+V passthrough + Ctrl+Space (emacs set-mark).'
    Write-Host "[emulin]   backup: $bak"
    Write-Host '[emulin]   (paste is Ctrl+Shift+V / right-click; EMULIN_NO_WT_SETUP=1 to skip)'
} catch {
    Show-Manual "Failed to update settings.json: $($_.Exception.Message)"
    exit 0
}
