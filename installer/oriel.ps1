# oriel.ps1 - installs Oriel on an Android TV over the network, or removes it again.
# Run it through "Install Oriel.bat" or "Remove Oriel.bat". Everything it changes on the TV is
# written to a backup first, and Remove puts those things back. "Install Oriel System.bat" also builds
# Oriel into the TV's system (rooted TVs), and "Restore Factory.bat" undoes that and then removes Oriel.
param(
    [ValidateSet("install", "remove", "system", "factory")] [string] $Action = "install",
    [string] $Tv = ""
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Tools = Join-Path $Root "tools"
$Adb = Join-Path $Tools "platform-tools\adb.exe"
$HomeClass = "com.muxover.oriel.home"
$MirrorPkg = "com.muxover.oriel.mirror"
# The maker's home screens on Hikeen boards; Remove Oriel always switches them back on.
$MakerHomes = @("com.zeasn.hikeen", "cn.zeasn.overseas.tv.hikeen.hisi352")
# Hikeen boards: the maker's services that draw its own panels for the remote's buttons.
$MakerPanels = @("com.hikeen.tvsettings/com.hikeen.tvsettings.menu.HotkeyService", "com.hikeen.tv.factorymenu/com.hikeen.tv.factorymenu.services.PowerMenuService")

function Say($text) { Write-Host $text }
function Step($text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Magenta }
function Fail($text) { Write-Host ""; Write-Host $text -ForegroundColor Red; Read-Host "Press Enter to close"; exit 1 }

# ---------- adb ----------

function Get-Adb {
    if (Test-Path $Adb) { return }
    Step "Downloading Android platform tools (adb), once"
    New-Item -ItemType Directory -Force -Path $Tools | Out-Null
    $zip = Join-Path $Tools "platform-tools.zip"
    Invoke-WebRequest -Uri "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $Tools -Force
    Remove-Item $zip
    if (-not (Test-Path $Adb)) { Fail "adb did not unpack. Check your internet connection and run this again." }
}

# Runs adb against the TV and returns its output as one string. Never stops the script on adb's stderr.
function A([string[]] $AdbArgs) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $out = & $Adb -s $script:Serial @AdbArgs 2>&1 | Out-String
    $ErrorActionPreference = $old
    return $out.Trim()
}

function Sh([string] $Command) { return A @("shell", $Command) }

function Root([string] $Command) {
    # Single quotes inside the command would end the su argument early, so they are refused here.
    if ($Command.Contains("'")) { throw "internal: root command contains a single quote" }
    return Sh "su 0 sh -c '$Command'"
}

function Connect-Tv {
    $last = Join-Path $Root "last-tv.txt"
    if (-not $Tv -and (Test-Path $last)) { $Tv = (Get-Content $last -Raw).Trim() }
    while ($true) {
        if (-not $Tv) {
            Say "Type the TV's IP address. On most TVs: Settings > Network > About, or your router's device list."
            $Tv = (Read-Host "TV IP address").Trim()
        }
        if ($Tv -notmatch ":") { $Tv = "${Tv}:5555" }
        $script:Serial = $Tv
        $old = $ErrorActionPreference; $ErrorActionPreference = "Continue"
        $res = & $Adb connect $Tv 2>&1 | Out-String
        $ErrorActionPreference = $old
        if ($res -match "connected to") {
            for ($i = 0; $i -lt 30; $i++) {
                $state = (A @("get-state"))
                if ($state -eq "device") { Set-Content -Path $last -Value $Tv; return }
                if ($i -eq 0 -and $state -match "unauthorized") { Say "Look at the TV: allow the connection from this computer (tick Always allow)." }
                Start-Sleep -Seconds 1
            }
            Fail "The TV did not allow the connection. Allow it on the TV screen and run this again."
        }
        Say "Could not reach $Tv. On the TV, turn on Developer options > Network debugging (or ADB debugging), then try again."
        $Tv = ""
    }
}

# ---------- what kind of TV is this ----------

function Read-Specs {
    $s = [ordered]@{}
    $s.model = Sh "getprop ro.product.model"
    $s.android = Sh "getprop ro.build.version.release"
    $s.sdk = [int](Sh "getprop ro.build.version.sdk")
    $s.abi = Sh "getprop ro.product.cpu.abi"
    $mem = Sh "cat /proc/meminfo"
    $s.ramMb = if ($mem -match "MemTotal:\s+(\d+)") { [int]([int64]$Matches[1] / 1024) } else { 0 }
    $gl = Sh "dumpsys SurfaceFlinger"
    $s.gpu = if ($gl -match "GLES: ([^\r\n]+)") { $Matches[1].Trim() } else { "unknown" }
    $size = Sh "wm size"
    $s.size = if ($size -match "Physical size: (\d+x\d+)") { $Matches[1] } else { "" }
    $s.override = if ($size -match "Override size: (\d+x\d+)") { $Matches[1] } else { "" }
    $dens = Sh "wm density"
    $s.density = if ($dens -match "Physical density: (\d+)") { [int]$Matches[1] } else { 0 }
    $s.root = (Sh "su 0 id 2>/dev/null") -match "uid=0"
    $s.hikeen = (Sh "pm list packages com.hikeen") -match "package:"
    return $s
}

# A GPU from the Mali-400 family or under 1.5 GB of RAM draws menus at 720p and scales them up; video is not affected.
function Needs-720($s) {
    if (-not $s.size) { return $false }
    $w = [int]($s.size.Split("x")[0])
    return ($w -gt 1280) -and (($s.gpu -match "Mali-4[05]0") -or ($s.ramMb -lt 1500))
}

# ---------- backup ----------

function Backup-Tv($s) {
    $dir = Join-Path $Root ("backups\" + ($script:Serial -replace "[:.]", "-"))
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $file = Join-Path $dir "before-oriel.json"
    if (Test-Path $file) { Say "Keeping the first backup ($file); it holds the TV as it was before Oriel."; return $file }
    $b = [ordered]@{}
    $b.home = Sh "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -n 1"
    $b.ime = Sh "settings get secure default_input_method"
    $b.a11y = Sh "settings get secure enabled_accessibility_services"
    $b.a11yOn = Sh "settings get secure accessibility_enabled"
    $b.listeners = Sh "settings get secure enabled_notification_listeners"
    $b.disabled = @((Sh "pm list packages -d") -split "`r?`n" | Where-Object { $_ } | ForEach-Object { $_ -replace "^package:", "" })
    $b.size = $s.override
    $b.density = Sh "wm density | grep Override | cut -d: -f2"
    $b.deviceName = Sh "settings get global device_name"
    $b.p2pName = Sh "settings get global wifi_p2p_device_name"
    $b.anim = @((Sh "settings get global window_animation_scale"), (Sh "settings get global transition_animation_scale"), (Sh "settings get global animator_duration_scale"))
    $b | ConvertTo-Json | Set-Content -Path $file -Encoding utf8
    Say "Backup saved: $file"
    return $file
}

# ---------- boot screens ----------
# The boot logo (shown by the boot loader), the boot animation and the boot video (some boards play
# a video instead of the animation). Each is changed only where this TV keeps it in a writable place,
# and its original is copied to the backup folder first. Needs root.

$BootDirs = @("/atv/bootvideo", "/oem/media", "/data/local")
$LogoBlockSize = 98304

# Copies a file the shell user may not be able to read to this computer. Stops the install if it cannot,
# since nothing is replaced without its original saved.
function Pull-AsRoot([string] $from, [string] $to) {
    Root "cp $from /data/local/tmp/oriel-pull && chmod 644 /data/local/tmp/oriel-pull" | Out-Null
    A @("pull", "/data/local/tmp/oriel-pull", $to) | Out-Null
    Root "rm -f /data/local/tmp/oriel-pull" | Out-Null
    if (-not (Test-Path $to)) { Fail "Could not save $from from the TV, so it was left unchanged. Run Install Oriel.bat again." }
}

function Backup-Dir { return (Join-Path $Root ("backups\" + ($script:Serial -replace "[:.]", "-"))) }

# Path of the boot animation this TV plays, if it can be replaced; else empty.
function Boot-AnimPath {
    $p = (Sh "getprop ro.prop.bootanim.path").Trim()
    if ($p -and (Root "test -f $p && touch $p && echo ok") -match "ok") { return $p }
    foreach ($d in $BootDirs) {
        if ((Root "test -f $d/bootanimation.zip && touch $d/bootanimation.zip && echo ok") -match "ok") { return "$d/bootanimation.zip" }
    }
    return ""
}

# Reads the first block of the logo partition and checks it holds a HiSilicon logo table with one JPEG.
# Returns the block bytes, or null when this TV keeps its boot logo some other way.
function Read-LogoBlock {
    if (-not ((Root "test -b /dev/block/by-name/logo && echo ok") -match "ok")) { return $null }
    $tmp = Join-Path $env:TEMP "oriel-logo-now.img"
    Root "dd if=/dev/block/by-name/logo of=/data/local/tmp/oriel-logo-now.img bs=4096 count=24 2>/dev/null; chmod 644 /data/local/tmp/oriel-logo-now.img" | Out-Null
    A @("pull", "/data/local/tmp/oriel-logo-now.img", $tmp) | Out-Null
    Root "rm -f /data/local/tmp/oriel-logo-now.img" | Out-Null
    if (-not (Test-Path $tmp)) { return $null }
    $b = [IO.File]::ReadAllBytes($tmp)
    Remove-Item $tmp
    if ($b.Length -ne $LogoBlockSize) { return $null }
    $ascii = [Text.Encoding]::ASCII.GetString($b, 0, 8192)
    $k = $ascii.IndexOf("LOGO_KEY_LEN")
    if ($ascii.IndexOf("LOGO_TABLE") -ne 8 -or $k -lt 0) { return $null }
    $len = [BitConverter]::ToInt32($b, $k + 36)
    if ($len -le 0 -or 8192 + $len -gt $LogoBlockSize) { return $null }
    if ($b[8192] -ne 0xFF -or $b[8193] -ne 0xD8 -or $b[8192 + $len - 2] -ne 0xFF -or $b[8192 + $len - 1] -ne 0xD9) { return $null }
    return ,$b
}

# Writes a full logo block and reads it back. Returns $true when the TV holds exactly these bytes.
function Write-LogoBlock([byte[]] $block, [string] $name) {
    $local = Join-Path $env:TEMP $name
    [IO.File]::WriteAllBytes($local, $block)
    A @("push", $local, "/data/local/tmp/$name") | Out-Null
    Remove-Item $local
    $out = Root "dd if=/data/local/tmp/$name of=/dev/block/by-name/logo bs=4096 count=24 conv=notrunc,fsync 2>/dev/null; sync; dd if=/dev/block/by-name/logo bs=4096 count=24 2>/dev/null | md5sum; md5sum < /data/local/tmp/$name; rm -f /data/local/tmp/$name"
    $sums = @($out -split "`r?`n" | ForEach-Object { ($_ -split "\s+")[0] } | Where-Object { $_ })
    return ($sums.Count -eq 2 -and $sums[0] -eq $sums[1])
}

function Install-BootScreens($s) {
    if (-not $s.root) { Say "Boot screens: skipped, they need full system access."; return }
    $dir = Backup-Dir

    $anim = Boot-AnimPath
    if ($anim) {
        $orig = Join-Path $dir "boot-anim-original.zip"
        if (-not (Test-Path $orig)) { Pull-AsRoot $anim $orig }
        $saved = Join-Path $dir "boot-anim-path.txt"
        if (-not (Test-Path $saved)) { Set-Content -Path $saved -Value $anim -Encoding ascii }
        A @("push", (Join-Path $Root "oriel-bootanimation.zip"), "/data/local/tmp/oriel-ba.zip") | Out-Null
        Root "cp /data/local/tmp/oriel-ba.zip $anim && chmod 644 $anim && rm -f /data/local/tmp/oriel-ba.zip && sync" | Out-Null
        Say "Boot animation: Oriel."
    } else { Say "Boot animation: this TV keeps it in a read-only place, so it stays as it is." }

    $video = "/atv/bootvideo/bootvideo.mp4"
    if ((Root "test -f $video && touch $video && echo ok") -match "ok") {
        $orig = Join-Path $dir "boot-video-original.mp4"
        if (-not (Test-Path $orig)) { Pull-AsRoot $video $orig }
        A @("push", (Join-Path $Root "oriel-bootvideo.mp4"), "/data/local/tmp/oriel-bv.mp4") | Out-Null
        Root "cp /data/local/tmp/oriel-bv.mp4 $video && chmod 644 $video && rm -f /data/local/tmp/oriel-bv.mp4 && sync" | Out-Null
        Say "Boot video: Oriel."
    }

    $block = Read-LogoBlock
    if ($null -eq $block) { Say "Boot logo: this TV stores it in a way Oriel does not know, so it stays as it is."; return }
    $orig = Join-Path $dir "boot-logo-original.img"
    if (-not (Test-Path $orig)) { [IO.File]::WriteAllBytes($orig, $block) }
    $jpg = [IO.File]::ReadAllBytes((Join-Path $Root "oriel-logo.jpg"))
    if (8192 + $jpg.Length -gt $LogoBlockSize) { Fail "internal: oriel-logo.jpg is too big for the logo block." }
    $new = New-Object byte[] $LogoBlockSize
    [Array]::Copy($block, 0, $new, 0, 8192)
    $k = [Text.Encoding]::ASCII.GetString($block, 0, 8192).IndexOf("LOGO_KEY_LEN")
    [Array]::Copy([BitConverter]::GetBytes([int]$jpg.Length), 0, $new, $k + 36, 4)
    [Array]::Copy($jpg, 0, $new, 8192, $jpg.Length)
    if (Write-LogoBlock $new "oriel-logo.img") { Say "Boot logo: Oriel." }
    else {
        # Never leave a half-written logo: put the original back.
        $back = Write-LogoBlock ([IO.File]::ReadAllBytes($orig)) "oriel-logo-back.img"
        Say ("Boot logo: writing it did not check out, so the original was put back ({0})." -f $(if ($back) { "verified" } else { "NOT verified, run Remove Oriel.bat" }))
    }
}

function Restore-BootScreens {
    $dir = Backup-Dir
    $anim = Join-Path $dir "boot-anim-original.zip"
    $path = Join-Path $dir "boot-anim-path.txt"
    if ((Test-Path $anim) -and (Test-Path $path)) {
        $p = (Get-Content $path -Raw).Trim()
        A @("push", $anim, "/data/local/tmp/oriel-ba.zip") | Out-Null
        Root "cp /data/local/tmp/oriel-ba.zip $p && chmod 644 $p && rm -f /data/local/tmp/oriel-ba.zip && sync" | Out-Null
        Say "Boot animation put back."
    }
    $video = Join-Path $dir "boot-video-original.mp4"
    if (Test-Path $video) {
        A @("push", $video, "/data/local/tmp/oriel-bv.mp4") | Out-Null
        Root "cp /data/local/tmp/oriel-bv.mp4 /atv/bootvideo/bootvideo.mp4 && chmod 644 /atv/bootvideo/bootvideo.mp4 && rm -f /data/local/tmp/oriel-bv.mp4 && sync" | Out-Null
        Say "Boot video put back."
    }
    $logo = Join-Path $dir "boot-logo-original.img"
    if (Test-Path $logo) {
        if (Write-LogoBlock ([IO.File]::ReadAllBytes($logo)) "oriel-logo-back.img") { Say "Boot logo put back." }
        else { Say "Boot logo: putting it back did not check out. Keep $logo and run Remove Oriel.bat again." }
    }
}

# ---------- install ----------

function Install-Apk($pkg, $apk) {
    $out = A @("install", "-r", $apk)
    if ($out -match "Success") { return }
    if ($out -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match|INSTALL_FAILED_VERSION_DOWNGRADE") {
        # Another app already uses this package name (a different build, or a newer version number): replace it.
        Say "Another app named $pkg is on the TV; replacing it with Oriel."
        A @("uninstall", $pkg) | Out-Null
        $out = A @("install", $apk)
        if ($out -match "Success") { return }
    }
    Fail "Installing $apk failed:`n$out"
}

function Install-Oriel([switch] $ThenSystem) {
    Step "Reading the TV"
    $s = Read-Specs
    Say ("{0}, Android {1}, {2}, {3} MB RAM, GPU {4}, screen {5}" -f $s.model, $s.android, $s.abi, $s.ramMb, $s.gpu, $s.size)
    if ($s.sdk -lt 29) { Fail "Oriel needs Android 10 or newer; this TV has Android $($s.android)." }
    Say ("Full system access (root): {0}. Hikeen board: {1}." -f $(if ($s.root) { "yes" } else { "no, so the own volume popup, mouse-mode block and input switching stay off" }), $s.hikeen)

    Step "Backing up the TV's settings"
    Backup-Tv $s | Out-Null

    $homePkg = if ($s.hikeen) { "com.zeasn.launcher" } else { "com.muxover.oriel" }
    $homeApk = Join-Path $Root $(if ($s.hikeen) { "oriel-home-hikeen.apk" } else { "oriel-home.apk" })
    $mirrorApk = Join-Path $Root "oriel-mirror.apk"
    foreach ($f in @($homeApk, $mirrorApk)) { if (-not (Test-Path $f)) { Fail "Missing $f. Keep the installer next to the Oriel .apk files." } }

    # The keeper script goes on first: Oriel starts it as soon as it runs.
    if ($s.root) {
        # Stop an older keeper first; Oriel starts the new one itself.
        Root "kill `$(cat /data/local/tmp/tvkeeper.pid) 2>/dev/null; rm -f /data/local/tmp/tvkeeper.pid" | Out-Null
        A @("push", (Join-Path $Root "tvkeeper.sh"), "/data/local/tmp/tvkeeper.sh") | Out-Null
        Root "chmod 755 /data/local/tmp/tvkeeper.sh" | Out-Null
    }

    Step "Installing Oriel and Oriel Mirror"
    Install-Apk $homePkg $homeApk
    Install-Apk $MirrorPkg $mirrorApk

    Step "Setting Oriel up"
    $homeActivity = "$homePkg/$HomeClass.HomeActivity"
    Sh "cmd package set-home-activity $homeActivity" | Out-Null
    $ime = "$homePkg/$HomeClass.KeyboardService"
    Sh "ime enable $ime; ime set $ime" | Out-Null
    $svc = "$homePkg/$HomeClass.WindowWatcher"
    $a11y = Sh "settings get secure enabled_accessibility_services"
    if ($a11y -notmatch [regex]::Escape($svc)) {
        $list = if ($a11y -and $a11y -ne "null") { "$a11y`:$svc" } else { $svc }
        Sh "settings put secure enabled_accessibility_services $list; settings put secure accessibility_enabled 1" | Out-Null
    }
    Sh "cmd notification allow_listener $homePkg/$HomeClass.MediaWatcher" | Out-Null
    foreach ($p in @($homePkg, $MirrorPkg)) { Sh "appops set $p SYSTEM_ALERT_WINDOW allow" | Out-Null }
    Sh "appops set $homePkg GET_USAGE_STATS allow; pm grant $homePkg android.permission.ACCESS_FINE_LOCATION; pm grant $homePkg android.permission.ACCESS_COARSE_LOCATION; pm grant $homePkg android.permission.READ_EXTERNAL_STORAGE" | Out-Null

    # The maker's home screens stay installed but switched off, so the TV can never fall back to them.
    $b = Get-Content (Join-Path $Root ("backups\" + ($script:Serial -replace "[:.]", "-") + "\before-oriel.json")) -Raw | ConvertFrom-Json
    $makerHome = ($b.home -split "/")[0]
    if ($makerHome -and $makerHome -ne $homePkg -and $makerHome -notmatch "^(android|com\.android\.tv\.settings)$") {
        Sh "pm disable-user --user 0 $makerHome" | Out-Null
        Say "Switched off the maker's home screen ($makerHome)."
    }
    # The maker's panels for the remote's Source, Sleep and picture buttons and for holding Power: switched
    # off, so Oriel's own screens open instead (the keeper starts them). Remove switches them back on.
    if ($s.root) {
        foreach ($c in $MakerPanels) {
            if ((Sh "pm list packages $($c.Split('/')[0])") -match "package:") {
                Root "pm disable $c" | Out-Null
            }
        }
        Say "Switched off the maker's Source, Sleep and Power panels."
    }

    # The maker's Miracast receiver drops the TV off Wi-Fi while it waits; Oriel does not use it.
    foreach ($p in @("com.hisilicon.miracast")) {
        if ((Sh "pm list packages $p") -match "(?m)^package:$([regex]::Escape($p))\r?$") {
            Sh "pm disable-user --user 0 $p" | Out-Null
            Say "Switched off the maker's Miracast receiver."
        }
    }

    if (Needs-720 $s) {
        $dens = [int]([math]::Round($s.density * 1280 / [int]($s.size.Split("x")[0])))
        if ($s.root) {
            # The keeper applies it after each boot and keeps it out of the saved display settings.
            Root "wm size reset; wm density reset; rm -f /data/local/tmp/oriel-display-clean.xml; echo 1280x720 $dens > /data/local/tmp/oriel-size" | Out-Null
        } else {
            Sh "wm size 1280x720; wm density $dens" | Out-Null
        }
        Say "This TV's graphics chip is slow at full HD, so menus are drawn at 720p for smooth motion. Video quality is not changed."
    }
    Install-BootScreens $s

    if ($s.ramMb -lt 1500) {
        Sh "settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; settings put global animator_duration_scale 0.5" | Out-Null
    }

    Step "Starting Oriel"
    Sh "am start -n $homeActivity" | Out-Null
    # The receiver service is private to its app, so it is started as root where possible, else by opening the app once.
    $svcStart = "am start-foreground-service -n $MirrorPkg/io.github.jqssun.airplay.service.AirPlayService -a io.github.jqssun.airplay.START_SERVER"
    if ($s.root) { Root $svcStart | Out-Null } else {
        Sh "am start -n $MirrorPkg/io.github.jqssun.airplay.MainActivity" | Out-Null
        Start-Sleep -Seconds 4
        Sh "am start -n $homeActivity" | Out-Null
    }
    if ($ThenSystem) { return }
    Write-Host ""
    Write-Host "Oriel is installed. To undo everything, run Remove Oriel.bat." -ForegroundColor Green
}

# ---------- remove ----------

function Remove-Oriel([switch] $NoRestart) {
    $file = Join-Path $Root ("backups\" + ($script:Serial -replace "[:.]", "-") + "\before-oriel.json")
    if (-not (Test-Path $file)) { Fail "No backup for this TV in $file, so there is nothing to put back. Oriel was installed from another computer or folder." }
    $b = Get-Content $file -Raw | ConvertFrom-Json

    Step "Putting the TV back the way it was"
    # A backup taken while an earlier Oriel or another home app of ours was installed holds our own home screen,
    # keyboard and screen size, not the maker's. Those values are never put back: the maker's defaults are.
    $ourBackup = ("$($b.home) $($b.ime)" -match "com\.muxover\.")
    if ($ourBackup) { Say "The backup was taken with an earlier Oriel installed; putting the maker's defaults back instead." }
    $nowDisabled = @((Sh "pm list packages -d") -split "`r?`n" | Where-Object { $_ } | ForEach-Object { $_ -replace "^package:", "" })
    $makerHome = ($b.home -split "/")[0]
    # The maker's own home screen always comes back on, whatever the backup says: without one, Android never
    # finishes starting.
    $homes = @($MakerHomes) + $(if ($makerHome -and -not $ourBackup) { @($makerHome) } else { @() })
    foreach ($p in $homes) { if ($nowDisabled -contains $p) { Sh "pm enable $p" | Out-Null } }
    foreach ($p in $nowDisabled) { if ($b.disabled -notcontains $p -or $MakerHomes -contains $p) { Sh "pm enable $p" | Out-Null } }
    if ($b.ime -and $b.ime -ne "null" -and $b.ime -notmatch "com\.muxover\.") { Sh "ime set $($b.ime)" | Out-Null } else { Sh "ime reset" | Out-Null }
    $a11y = @(("$($b.a11y)" -split ":") | Where-Object { $_ -and $_ -ne "null" -and $_ -notmatch "com\.muxover\." }) -join ":"
    if (-not $a11y) { $a11y = '""' }
    Sh "settings put secure enabled_accessibility_services $a11y" | Out-Null
    if ($b.a11yOn -and $b.a11yOn -ne "null") { Sh "settings put secure accessibility_enabled $($b.a11yOn)" | Out-Null }
    if ($b.size -and -not $ourBackup) { Sh "wm size $($b.size)" | Out-Null } else { Sh "wm size reset" | Out-Null }
    if ($b.density -and $b.density.Trim() -and -not $ourBackup) { Sh "wm density $($b.density.Trim())" | Out-Null } else { Sh "wm density reset" | Out-Null }
    Sh ("settings put global window_animation_scale {0}; settings put global transition_animation_scale {1}; settings put global animator_duration_scale {2}" -f $b.anim[0], $b.anim[1], $b.anim[2]) | Out-Null
    if ($b.deviceName -and $b.deviceName -ne "null") { Sh "settings put global device_name '$($b.deviceName)'" | Out-Null }
    if ($b.p2pName -and $b.p2pName -ne "null") { Sh "settings put global wifi_p2p_device_name '$($b.p2pName)'" | Out-Null } else { Sh "settings delete global wifi_p2p_device_name" | Out-Null }
    if ((Sh "su 0 id 2>/dev/null") -match "uid=0") {
        Restore-BootScreens
        foreach ($c in $MakerPanels) { Root "pm enable $c" | Out-Null }
        Root "kill `$(cat /data/local/tmp/tvkeeper.pid) 2>/dev/null; rm -f /data/local/tmp/tvkeeper.sh /data/local/tmp/tvkeeper.pid /data/local/tmp/oriel-size /data/local/tmp/oriel-display-clean.xml /data/local/tmp/oriel-home; pkill -f logcat.-v.brief.-s.PhoneWindowManagerEx" | Out-Null
    }

    Step "Removing Oriel"
    foreach ($p in @("com.muxover.oriel", "com.zeasn.launcher", $MirrorPkg)) {
        if ((Sh "pm list packages $p") -match "(?m)^package:$([regex]::Escape($p))\r?$") {
            # On Hikeen boards com.zeasn.launcher is Oriel only when it came from this installer.
            if ($p -eq "com.zeasn.launcher" -and (Sh "dumpsys package $p | grep -c $HomeClass") -eq "0") { continue }
            A @("uninstall", $p) | Out-Null
            Say "Removed $p"
        }
    }
    if ($NoRestart) { return }
    Write-Host ""
    Write-Host "The TV is back to how it was before Oriel. Restarting it now." -ForegroundColor Green
    A @("reboot") | Out-Null
}

# ---------- system ----------
# Oriel built into the TV's system partition. Each change goes through the boot watchdog on the TV: it is
# listed first, the TV restarts, and the watchdog keeps it only when Android starts and stays up; otherwise it
# puts the change back by itself and restarts. Kept changes go into a journal that Restore Factory reads.

$SysDir = Join-Path $Root "system"
$Tmp = "/data/local/tmp"
# HiSilicon boards from Hikeen: the maker's apps that Oriel replaces, in the batches tested on a Hi3751V553.
$MakerBatches = @(
    @("/system/app/Exoplayer", "/system/priv-app/ZeasnLauncher11", "/system/app/TvStore_11", "/system/app/Miracast",
      "/system/priv-app/HkHotelMenu", "/system/priv-app/HkCleanMaster", "/system/priv-app/HkGuide", "/system/app/HkGalleryL",
      "/system/app/HkMusic", "/system/priv-app/HkVideoPlayer", "/system/priv-app/MediaPlayer", "/system/app/HiDMP"),
    @("/system/app/FotaUpdate", "/system/priv-app/FactoryAutomationService", "/system/app/HiDMR", "/system/app/HiRMService",
      "/system/priv-app/HkSetting", "/system/priv-app/HkTvSettings", "/system/app/HkFactoryMenu"),
    @("/system/priv-app/HkSingleKey")
)
# Partitions that are not backed up: the user's data and the logs and caches that rebuild themselves.
$SkipParts = @("userdata", "cache", "log", "mmcblk0")

# Waits for the TV to finish starting after a restart. Returns $false after 8 minutes.
function Wait-Boot {
    Start-Sleep -Seconds 30
    for ($i = 0; $i -lt 90; $i++) {
        $old = $ErrorActionPreference; $ErrorActionPreference = "Continue"
        & $Adb connect $script:Serial 2>&1 | Out-Null
        $ErrorActionPreference = $old
        if ((Sh "getprop sys.boot_completed") -eq "1") { return $true }
        Start-Sleep -Seconds 5
    }
    return $false
}

# Restarts the TV and returns the watchdog's verdict on the change it was given: "kept" or "undone".
function Restart-AndJudge([string] $what) {
    Say "Restarting the TV to try it ($what)."
    A @("reboot") | Out-Null
    if (-not (Wait-Boot)) { Fail "The TV did not come back within 8 minutes. Leave it on: its watchdog puts the last change back by itself. Then run Restore Factory.bat." }
    for ($i = 0; $i -lt 120; $i++) {
        $v = Root "cat $Tmp/oriel-watchdog-last 2>/dev/null"
        if ($v -match "kept|undone") { return $Matches[0] }
        Start-Sleep -Seconds 5
        if ((Sh "getprop sys.boot_completed") -ne "1") { Wait-Boot | Out-Null }
    }
    Fail "The watchdog gave no verdict on $what. Run Restore Factory.bat."
}

# Runs one listed change, then restarts and checks it. Skips the restart when nothing was listed.
function Apply-Change([string] $what, [string] $command) {
    Step $what
    Root "rm -f $Tmp/oriel-watchdog-last $Tmp/oriel-pending" | Out-Null
    $out = Root $command
    $out -split "`r?`n" | Where-Object { $_ -match "^(added|moved|skip|FAILED)" } | ForEach-Object { Say "  $_" }
    if ((Root "test -s $Tmp/oriel-pending && echo yes") -notmatch "yes") { Say "Nothing to change."; return }
    $v = Restart-AndJudge $what
    if ($v -eq "kept") { Say "Kept: Android started and stayed up." }
    else { Fail "Android did not start with this change, so the TV put it back by itself and is as it was before this step. Nothing after it was done." }
}

# Copies every partition except the user's data to this computer, with a checksum read on the TV and checked here.
function Backup-Partitions {
    $dir = Join-Path (Backup-Dir) "partitions"
    $fp = Sh "getprop ro.build.fingerprint"
    $info = Join-Path $dir "device-info.txt"
    if ((Test-Path (Join-Path $dir "SHA256SUMS")) -and (Test-Path $info) -and ((Get-Content $info -Raw) -match [regex]::Escape($fp))) {
        Say "Keeping the partition backup already in $dir."
        return
    }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $names = @((Root "ls /dev/block/by-name") -split "\s+" | Where-Object { $_ -and ($SkipParts -notcontains $_) })
    $names += @("mmcblk0boot0", "mmcblk0boot1")
    $free = [int64](Sh "df -k /data | tail -n 1 | awk '{print `$4}'")
    if ($free -lt 2097152) { Fail "The TV needs 2 GB free for the backup, one partition at a time; it has $([int]($free / 1024)) MB." }
    $sums = @()
    foreach ($n in $names) {
        $dev = if ($n -like "mmcblk0boot*") { "/dev/block/$n" } else { "/dev/block/by-name/$n" }
        $local = Join-Path $dir "$n.img"
        Say "  $n"
        $tvSum = (Root "dd if=$dev of=$Tmp/oriel-part.img bs=1048576 2>/dev/null && chmod 644 $Tmp/oriel-part.img && sha256sum $Tmp/oriel-part.img") -split "\s+" | Select-Object -First 1
        A @("pull", "$Tmp/oriel-part.img", $local) | Out-Null
        Root "rm -f $Tmp/oriel-part.img" | Out-Null
        if (-not (Test-Path $local)) { Fail "Could not copy the $n partition to this computer. Nothing on the TV was changed." }
        $pcSum = (Get-FileHash -Algorithm SHA256 $local).Hash.ToLower()
        if ($pcSum -ne $tvSum) { Fail "The copy of $n does not match the TV ($pcSum vs $tvSum). Nothing on the TV was changed; run this again." }
        $sums += "$pcSum  $n.img"
    }
    Set-Content -Path (Join-Path $dir "SHA256SUMS") -Value $sums -Encoding ascii
    Set-Content -Path $info -Value @($fp, (Sh "cat /proc/cmdline")) -Encoding ascii
    Say "Partition backup saved: $dir"
}

function Install-SystemTools {
    foreach ($f in @("oriel-watchdog.rc", "oriel-watchdog.sh", "oriel-remove.sh", "oriel-add.sh", "oriel-factory.sh")) {
        A @("push", (Join-Path $SysDir $f), "$Tmp/$f") | Out-Null
    }
    Root ("mount -o rw,remount / && cp $Tmp/oriel-watchdog.rc /system/etc/init/oriel-watchdog.rc && chmod 644 /system/etc/init/oriel-watchdog.rc" +
        " && for f in oriel-watchdog.sh oriel-remove.sh oriel-add.sh oriel-factory.sh; do cp $Tmp/`$f /system/etc/`$f; chmod 755 /system/etc/`$f; done" +
        " && chown 0:0 /system/etc/init/oriel-watchdog.rc /system/etc/oriel-*.sh && chcon u:object_r:system_file:s0 /system/etc/init/oriel-watchdog.rc /system/etc/oriel-*.sh; sync") | Out-Null
    if ((Root "test -x /system/etc/oriel-watchdog.sh && test -f /system/etc/init/oriel-watchdog.rc && echo ok") -notmatch "ok") {
        Fail "Could not put the boot watchdog on the TV, so its system was left unchanged."
    }
    Say "Boot watchdog in place."
}

function Install-System {
    Install-Oriel -ThenSystem
    $s = Read-Specs
    if (-not $s.root) { Fail "Building Oriel into the system needs full system access (root), which this TV does not give. Oriel stays installed as an app." }
    if ($s.sdk -lt 31) { Fail "Building Oriel into the system is made for Android 12 or newer; this TV has Android $($s.android). Oriel stays installed as an app." }
    if ((Root "mount -o rw,remount / && echo ok") -notmatch "ok") { Fail "This TV's system partition cannot be opened for writing. Oriel stays installed as an app." }
    $homePkg = if ($s.hikeen) { "com.zeasn.launcher" } else { "com.muxover.oriel" }
    $homeApk = Join-Path $Root $(if ($s.hikeen) { "oriel-home-hikeen.apk" } else { "oriel-home.apk" })

    Step "Backing up every partition to this computer (about 2 GB; several minutes)"
    Backup-Partitions

    Step "Putting the boot watchdog on the TV"
    Install-SystemTools

    A @("push", $homeApk, "$Tmp/oriel-sys-home.apk") | Out-Null
    A @("push", (Join-Path $SysDir "privapp-permissions-oriel.xml"), "$Tmp/oriel-privapp.xml") | Out-Null
    $cmd = "sh /system/etc/oriel-add.sh $Tmp/oriel-privapp.xml /system/etc/permissions/privapp-permissions-oriel.xml"
    if ((Root "test -d /system/priv-app/OrielHome && echo yes") -match "yes") {
        $cmd = "sh /system/etc/oriel-remove.sh /system/priv-app/OrielHome; " + $cmd
    }
    $cmd += "; sh /system/etc/oriel-add.sh $Tmp/oriel-sys-home.apk /system/priv-app/OrielHome/OrielHome.apk; mount -o rw,remount /; chmod 755 /system/priv-app/OrielHome; chcon u:object_r:system_file:s0 /system/priv-app/OrielHome; sync"
    Apply-Change "Building Oriel into the system" $cmd

    if ($s.hikeen -and (Sh "pm list packages com.hisilicon.tv.service") -match "package:") {
        $n = 0
        foreach ($batch in $MakerBatches) {
            $n++
            $present = @($batch | Where-Object { (Root "test -d $_ && echo yes") -match "yes" })
            if ($present.Count -eq 0) { continue }
            Apply-Change ("Taking out the maker's apps, part {0} of {1}" -f $n, $MakerBatches.Count) ("sh /system/etc/oriel-remove.sh " + ($present -join " "))
        }
    } else { Say "Not a Hikeen board: the maker's apps stay; only Oriel's own parts are built in." }

    foreach ($f in @("OrielFramework.apk", "fonts_customization.xml", "Geist-Regular.ttf", "Geist-Medium.ttf", "Geist-SemiBold.ttf", "Geist-Bold.ttf")) {
        A @("push", (Join-Path $SysDir $f), "$Tmp/oriel-$f") | Out-Null
    }
    $look = "sh /system/etc/oriel-add.sh $Tmp/oriel-OrielFramework.apk /system/product/overlay/OrielFramework/OrielFramework.apk"
    if ((Root "test -e /system/product/etc/fonts_customization.xml && echo yes") -notmatch "yes") {
        $look += " $Tmp/oriel-fonts_customization.xml /system/product/etc/fonts_customization.xml"
        foreach ($w in @("Regular", "Medium", "SemiBold", "Bold")) { $look += " $Tmp/oriel-Geist-$w.ttf /system/product/fonts/Geist-$w.ttf" }
    } else { Say "This TV already has its own extra fonts list, so Geist is left out and dialogs keep their font." }
    $look += "; mount -o rw,remount /; chmod 755 /system/product/overlay/OrielFramework /system/product/fonts 2>/dev/null; chcon u:object_r:system_file:s0 /system/product/overlay/OrielFramework /system/product/fonts 2>/dev/null; sync"
    Apply-Change "Giving Android's own dialogs Oriel's look" $look
    Root "rm -f $Tmp/oriel-sys-home.apk $Tmp/oriel-privapp.xml $Tmp/oriel-OrielFramework.apk $Tmp/oriel-fonts_customization.xml $Tmp/oriel-Geist-*.ttf" | Out-Null

    Step "Checking the TV"
    $focus = Sh "dumpsys window | grep -m1 mCurrentFocus"
    $sysApp = (Sh "pm list packages -s") -match "package:$([regex]::Escape($homePkg))"
    Say ("Oriel home on screen: {0}. Oriel part of the system: {1}." -f ($focus -match "oriel.home"), $sysApp)
    Write-Host ""
    Write-Host "Oriel is built into this TV's system. To put the TV back as the maker shipped it, run Restore Factory.bat." -ForegroundColor Green
}

function Restore-Factory {
    $hasRoot = (Sh "su 0 id 2>/dev/null") -match "uid=0"
    $built = $hasRoot -and ((Root "test -x /system/etc/oriel-factory.sh && echo yes") -match "yes")
    if ($built) {
        Step "Putting the maker's system back"
        $out = Root "sh /system/etc/oriel-factory.sh"
        $out -split "`r?`n" | Where-Object { $_ } | ForEach-Object { Say "  $_" }
        if ($out -match "FAILED|MISSING") {
            Fail ("Some of the maker's files could not be put back (above). The full copy of every partition is in " + (Join-Path (Backup-Dir) "partitions") + ". Nothing else was changed.")
        }
        Sh "settings delete global hidden_api_blacklist_exemptions" | Out-Null
    } else { Say "Oriel is not built into this TV's system; removing the app only." }
    Remove-Oriel -NoRestart

    Step "Restarting the TV and checking it starts"
    A @("reboot") | Out-Null
    $homeApp = if (Wait-Boot) { Sh "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1" } else { "" }
    if (-not $homeApp -or $homeApp -match "com\.muxover\.|ResolverActivity|No activity") {
        Write-Host ""
        Write-Host "The TV did not start back up with the maker's home screen." -ForegroundColor Red
        Write-Host "Unplug it, hold the power button on the TV itself, plug it back in and keep holding until Android Recovery shows."
        Write-Host "Choose Wipe data/factory reset, then Reboot system now. The system files are already the maker's."
        return
    }
    if ($built) { Root "mount -o rw,remount / && rm -f /system/etc/init/oriel-watchdog.rc /system/etc/oriel-watchdog.sh /system/etc/oriel-remove.sh /system/etc/oriel-add.sh /system/etc/oriel-factory.sh; sync; mount -o ro,remount / 2>/dev/null" | Out-Null }
    Write-Host ""
    Write-Host "The TV is back to how it was before Oriel, on the maker's home screen ($homeApp)." -ForegroundColor Green
}

# ---------- main ----------

Get-Adb
Connect-Tv
switch ($Action) {
    "install" { Install-Oriel }
    "remove" { Remove-Oriel }
    "system" { Install-System }
    "factory" { Restore-Factory }
}
Read-Host "Press Enter to close"
