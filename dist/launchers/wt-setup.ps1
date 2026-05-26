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

# 3. Idempotent: if any ctrl+v binding already exists (ours or the user's),
#    do nothing and stay quiet.
if ($text -match '(?i)"ctrl\+v"') { exit 0 }

# 4. Insert the entry right after the opening '[' of the top-level "actions"
#    (or legacy "keybindings") array. Preserve the file's newline style.
$nl = if ($text -match "`r`n") { "`r`n" } else { "`n" }
$entry = '{ "command": "unbound", "keys": "ctrl+v" }'
$rxEmpty = [regex]'(?is)("(?:actions|keybindings)"\s*:\s*)\[\s*\]'
$rxArray = [regex]'(?is)("(?:actions|keybindings)"\s*:\s*\[)'
if ($rxEmpty.IsMatch($text)) {
    # empty array -> clean single-entry array (no trailing comma)
    $new = $rxEmpty.Replace($text, ('${1}[' + $nl + '        ' + $entry + $nl + '    ]'), 1)
} elseif ($rxArray.IsMatch($text)) {
    # array with entries -> insert ours first, keep the rest (WT allows the
    #   resulting trailing comma)
    $new = $rxArray.Replace($text, ('${1}' + $nl + '        ' + $entry + ','), 1)
} else {
    Show-Manual 'No "actions" array found in settings.json; add the entry manually.'
    exit 0
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
