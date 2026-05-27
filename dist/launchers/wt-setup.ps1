# wt-setup.ps1 -- Emulin: let Ctrl+V reach emacs/vim under Windows Terminal.
#
# Windows Terminal binds Ctrl+V to "paste" by default and consumes it, so
# emacs C-v (scroll-up) / vim CTRL-V (visual-block) never arrive. WT reads a
# single fixed settings.json and has NO command-line way to pass keybindings,
# so we add an idempotent  { "command":"unbound","keys":"ctrl+v" }  entry to
# that file (backing it up first). WT hot-reloads settings.json on save, so
# the change takes effect immediately. Opt out with EMULIN_NO_WT_SETUP=1
# (checked by the caller). Invoked by emulin.bat.
#
# Safe by design:
#   - text insertion only (never re-serializes the JSON; comments preserved)
#   - skips if a ctrl+v binding already exists (idempotent; respects the user)
#   - on any unrecognized structure / error, leaves the file untouched and
#     prints the manual fragment path.
#
# -SettingsPath overrides auto-detection (used by the test harness).

param([string]$SettingsPath)

$ErrorActionPreference = 'Stop'
$fragPath = if ($PSScriptRoot) { Join-Path $PSScriptRoot 'windows-terminal-settings.jsonc' } else { $null }

function Show-Manual($why) {
    Write-Host "[emulin] $why"
    Write-Host '[emulin] To enable emacs/vim Ctrl+V manually, open Windows Terminal'
    Write-Host '[emulin] settings (Ctrl+,) and add to its "actions" array:'
    Write-Host '[emulin]     { "command": "unbound", "keys": "ctrl+v" }'
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

# 2. Read raw text (preserve exactly).
$text = Get-Content -LiteralPath $settings -Raw

# 3/4. Decide the edit. WT has two binding schemas:
#   - new (1.19+): "keybindings" maps a key to an action "id"
#       { "id": "User.paste", "keys": "ctrl+v" }   ->  unbind: "id":"unbound"
#   - legacy: inline command + keys (in "actions" or "keybindings")
#       { "command": "paste", "keys": "ctrl+v" }   ->  unbind: command "unbound"
# Default WT bindings (incl. ctrl+v=paste) live in a hidden defaults.json,
# so a user-level "unbound" entry is what overrides them.
#
# Find an existing ctrl+v binding entry: a {...} (no nested braces) that
# contains "keys": "ctrl+v". This catches the user's paste binding.
$rxEntry = [regex]'\{[^{}]*"keys"\s*:\s*"ctrl\+v"[^{}]*\}'
$m = $rxEntry.Match($text)
if ($m.Success) {
    if ($m.Value -match '(?i)unbound') { exit 0 }   # already unbound: idempotent
    # Replace the whole entry with an unbound binding of the same shape.
    if ($m.Value -match '"id"\s*:') {
        $newEntry = '{ "id": "unbound", "keys": "ctrl+v" }'
    } else {
        $newEntry = '{ "command": "unbound", "keys": "ctrl+v" }'
    }
    $new = $text.Substring(0, $m.Index) + $newEntry + $text.Substring($m.Index + $m.Length)
} else {
    # No ctrl+v binding yet: add one. Prefer "keybindings" (new schema,
    # id-based); fall back to "actions" (legacy inline). Preserve newline style.
    $nl = if ($text -match "`r`n") { "`r`n" } else { "`n" }
    $rxKb = [regex]'(?is)("keybindings"\s*:\s*\[)'
    $rxAc = [regex]'(?is)("actions"\s*:\s*\[)'
    if ($rxKb.IsMatch($text)) {
        $new = $rxKb.Replace($text, ('${1}' + $nl + '        { "id": "unbound", "keys": "ctrl+v" },'), 1)
    } elseif ($rxAc.IsMatch($text)) {
        $new = $rxAc.Replace($text, ('${1}' + $nl + '        { "command": "unbound", "keys": "ctrl+v" },'), 1)
    } else {
        Show-Manual 'No "keybindings"/"actions" array in settings.json; add the entry manually.'
        exit 0
    }
}
if ($new -eq $text) {
    Show-Manual 'Could not update settings.json automatically.'
    exit 0
}

# 5. Back up once, then write back as UTF-8 (no BOM; WT tolerates both).
try {
    $bak = "$settings.emulin-bak"
    if (-not (Test-Path $bak)) { Copy-Item -LiteralPath $settings -Destination $bak -Force }
    [System.IO.File]::WriteAllText($settings, $new, (New-Object System.Text.UTF8Encoding $false))
    Write-Host '[emulin] Windows Terminal: enabled Ctrl+V passthrough for emacs/vim.'
    Write-Host "[emulin]   backup: $bak"
    Write-Host '[emulin]   (paste is Ctrl+Shift+V / right-click; EMULIN_NO_WT_SETUP=1 to skip)'
} catch {
    Show-Manual "Failed to update settings.json: $($_.Exception.Message)"
    exit 0
}
