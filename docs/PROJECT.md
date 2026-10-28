# Oriel project

Oriel is a new home, mirroring and media system for my Android TV, built with Claude in three nights: a VIEW ML43VIEWA620 on a HiSilicon Hi3751V553 board, rooted, running Android 12. It replaces the maker's home screen and settings, adds its own keyboard, phone remote, player and HDMI viewer, and Oriel Mirror, one app for AirPlay and Windows mirroring, and it can build itself into the TV's system.

This page explains how it works on that TV and lists the problems it hit and how each was fixed. It is not a guide for installing Oriel on other TVs, and nothing here is supported. If an AI is adapting Oriel to another TV, [Adapting it to another TV](#adapting-it-to-another-tv) lists everything that depends on this one.

## How it works

### Two apps and two helpers

- Oriel (`home`) is the home screen. It is set as the TV's home app, keyboard, accessibility service and notification listener. The accessibility service tells it when an app window opens and when a remote key is pressed, so it knows when the TV is idle and can press Update all in Google Play. The notification listener lets it read what any app is playing for Now Playing.
- Oriel Mirror (`mirror`) is the AirPlay receiver. It runs as its own service and is always listening, so you mirror from any screen without opening anything on the TV.
- The root helper. Some things an app can't do on its own: press keys for the phone remote, run the phone's virtual mouse, replace the system volume bar, switch inputs, load the TV's picture libraries, power off without the maker's dialog. For these Oriel connects to the TV's own ADB service on `127.0.0.1:5555` and starts a small helper as root through `app_process`. The Windows mirroring receiver starts the same way.
- The keeper (`tvkeeper.sh`) runs as root. It keeps the remote's mouse mode off, restarts Oriel Mirror if the system stops it, applies the 720p menu size once the boot animation is over, and opens Oriel's screens for the remote's own buttons.

### Keeping the TV recoverable

- Before changing anything, the installer writes `backups\<tv>\before-oriel.json` on the PC: the home screen, keyboard, accessibility services, notification listeners, disabled apps, screen size and density, TV name and animation speeds. Settings that belong to an earlier Oriel are never put back from it; the maker's defaults are.
- The maker's home screen is switched off, not uninstalled, and every way of removing Oriel switches it back on.
- Each boot screen is replaced only where the TV keeps it in a writable place, and its original is copied to the PC first. The boot logo is read back after writing, and if it doesn't match, the original goes straight back.
- `Install Oriel System.bat` copies every partition except user data to the PC before it touches the system.
- Every system change goes through a boot watchdog (below).

## What it does

### Home

- Rows by kind of app: Watch, Listen, Sports, News and talks, Kids, Your media, Games and More apps, then Connect (Mirror, Inputs, Media) and System (All apps, Update apps, Google Play, the maker's App Store when it is on the TV, Chrome, Settings). Only installed apps show.
- Each app shows its official 512 px icon from its Google Play page, downloaded once and kept on the TV, and a short line on what it is.
- Opening an app shows a short launch screen with the app's colours and a progress bar, which fades as soon as the app's window is up. Coming back, the home's first frame is already the app's colour, so the bare home never flashes.

### Settings

| Group | Sections |
| --- | --- |
| Connections | Network (Wi-Fi, connection details, data used, speed test), Bluetooth, Screen mirroring |
| Look and feel | Appearance (accent colour, clock on the home, app names under tiles), Clock and date (12 or 24 hours, AM and PM, date style, Celsius or Fahrenheit), Screen saver (when it starts, and whether it shows weather, the next prayer and live football), Night mode, Name and place |
| On the screen saver | Prayer times (9 calculation methods, standard or Hanafi Asr), Football (48 leagues and cups, and teams found by typing part of their name) |
| This TV | Remote and keyboard, Picture and sound, Apps (Open, Force stop, Clear cache, Uninstall), Storage, About |

### Keyboard and phone remote

- The Oriel keyboard, in English, Arabic and symbols, is used in every app. If Android or an app switches to another keyboard, Oriel switches back.
- The phone remote is a page on the TV at `http://<tv-ip>:8080` with arrows, OK, Back, Home, playback, volume and power.
- Its mouse is a virtual USB mouse made with `/system/bin/uinput`, so Android draws its own pointer and every app gets real clicks and scrolling.
- Typing goes into whatever text field is open in any app, through the Oriel keyboard's own connection to that field. Only the changed part is sent, and Enter runs the field's action (Search, Go, Send). While the page is open, the TV shows a small "Type on your phone" bar in place of the keyboard.
- Send to TV opens a link in the app made for it. Files of any kind can go several at once: photos, videos and songs open in Oriel, and other files are kept in Downloads/Oriel.

### Mirroring

- iPhone, iPad and Mac mirror through Oriel Mirror, an AirPlay receiver.
- Windows mirrors with Win + K, through the TV's own Wi-Fi Display engine (below). The TV stays on the home Wi-Fi while it waits and while it plays.
- Android phones cannot mirror to Oriel, and there is no Google Cast receiver.

### Media

- A grid of folders, videos, music and photos from USB drives and the TV's storage, each under its own title, with a frame from each video, each photo and each song's cover. Videos you stopped part way show a progress bar and wait under Continue watching.
- The player resumes where you stopped, skips 10 seconds with left and right (further when held), and plays the next video in the folder. Down opens Start over, subtitles and sound tracks.
- Subtitles come from `.srt` files whose name starts with the video's name (`Film.srt`, `Film.ar.srt`), read by Oriel itself so files in UTF-8, UTF-16 or the older Arabic Windows-1256 encoding all show correctly. Subtitle tracks inside the video work too.
- Music plays the folder in order or shuffled, with cover art, title, artist and album from the file, colours taken from the art, and repeat for the list or one song.
- Photos go forward and back with left and right, and OK starts a slideshow.

### Screen saver, inputs and the rest

- The screen saver shows a large clock, the weather, the next prayer and a live match over slowly moving colours, or Now Playing when music is on. It starts only from the home screen.
- Oriel's own HDMI and AV viewer, input bar, volume popup, power screen and sleep timer. The remote's Source, Sleep, picture and Power buttons open these.
- Picture and sound settings: picture and sound mode, backlight, brightness, contrast, colour, sharpness and colour temperature.
- Night mode warms and dims the picture over every app during chosen hours.
- Oriel's boot logo, boot animation and boot video, drawn by `installer/boot/make_boot.py`.

## Windows mirroring

The maker's own Miracast app (`com.hisilicon.miracast`) disconnects the TV from the router for as long as it waits for a PC, and it is platform-signed, so it can't be changed. Oriel's receiver, `WindowsReceiver`, runs as root through `app_process` and uses the same engine without that app:

1. It keeps an autonomous Wi-Fi Direct group on `p2p0`, on the same channel as the router (`wpa_cli p2p_group_add freq=<router channel>`). The driver allows one group owner beside the normal connection on one channel, so Wi-Fi stays up.
2. It sets the Wi-Fi Direct `device_name` to the TV's name. Without it, Android shows a random `Android_XXXX`.
3. It advertises a Wi-Fi Display sink (`wfd_subelem_set 0 000600111c440032`: primary sink, session available, control port 7236).
4. When Windows asks to join (`P2P-PROV-DISC-PBC-REQ`), it answers with `wps_pbc`. When the PC is in, `dnsmasq` gives it an address in `192.168.49.0/24`.
5. It starts the sink: a class named `com.hisilicon.miracast.ndk.SinkNative`, so the natives in `/system/lib/libsink_jni.so` bind to it, then `setHdcpMode(1)`, `initSink(<pc address>, 7236)`, `setAudioMode("AAC")` and `startSink`, and `initPlayer` when the engine reports it has started.
6. The picture plays on the TV's video plane, under the graphics. Oriel opens a transparent screen with the PC's name over it. Back, or leaving that screen, stops the session.

Microsoft's Miracast over Infrastructure, which mirrors over the router, was tried first. It needs a vendor attribute inside the Wi-Fi Direct frames, but the TV's `wpa_supplicant` can only add it as a separate element. Windows ignored it and used classic Wi-Fi Direct anyway.

Windows sends 1024x768 when it duplicates its screen. Win + P, Second screen only, gives 1080p.

## Built into the system

`Install Oriel System.bat` builds Oriel into the system partition. Oriel goes to `/system/priv-app/OrielHome` with its permissions in `/system/etc/permissions/privapp-permissions-oriel.xml`, so it uses its own rights for secure settings, restarting and turning off, app management and reading the remote's buttons. The TV's platform key is the board maker's own, so Oriel can't be signed as a platform app; a privileged app is as far as it goes.

The maker's apps are moved to `/data/local/tmp/oriel-removed`, not deleted. A static overlay of the `android` package in `/system/product/overlay/OrielFramework` gives Android's dialogs Oriel's colours and corners, and Geist is registered in `/system/product/etc/fonts_customization.xml`. The permission prompt keeps the TV's colours: it is a Mainline module that only allows its own style list to be overlaid.

Every change goes through `/system/etc/oriel-watchdog.sh`, started by `/system/etc/init/oriel-watchdog.rc` at each boot. A change is first listed in `/data/local/tmp/oriel-pending`. After the restart the watchdog keeps it when Android starts and `system_server` stays up for a minute, and adds it to `/data/local/tmp/oriel-journal`. If Android has not started after five minutes, it moves everything back, newest first, and restarts.

`Restore Factory.bat` runs `/system/etc/oriel-factory.sh`, which undoes the journal, then removes Oriel and restarts the TV. Only once Android has started on the maker's home screen does it remove the watchdog. If the TV does not come back, holding the TV's power button while plugging it in opens Android Recovery.

## Problems and fixes

- **The maker kills other launchers.** Hikeen's `HikeenPropertyInject` in `services.jar` force-closes the previous app on every launch unless its package is on a built-in list. Oriel's Hikeen build is named `com.zeasn.launcher`, a name on that list that isn't installed on this TV.
- **The graphics chip.** The Mali-450 is slow, so on a TV wider than 1280 px with a Mali-400 or Mali-450, or under 1.5 GB of RAM, menus are drawn at 1280x720 and scaled up; video keeps full resolution. On top of that: no shadows on moving tiles, the home's backdrop baked into one small picture instead of four layers, colour fields painted once at low resolution and only moved, and system animations at half length.
- **Banding and a boxed shine.** Dark gradients stepped into visible bands on the panel; a faint grain over each gradient breaks the steps up. The light that crosses the launch screen's label lit a rectangle around the text; it is now masked to the letters.
- **The boot logo jumped to a corner.** `wm size` and `wm density` also save `display_size_forced` and `display_density_forced`, which Android applied at the end of the next boot while the boot animation's last frame was still showing. The keeper deletes both after applying the menu size.
- **Home flickered after a restart.** It was built three times: at full size, after the size change and after the density change. The keeper now announces the size in `debug.oriel.menu_size`, and the home stays black until both match, then fades in once.
- **No usage events.** This TV doesn't record which app is in front, so readiness comes from Oriel's accessibility service. The TV also drops that service and Oriel's keyboard after an Oriel update; Oriel switches both back on when the home shows.
- **Picture settings.** `HitvManager` lives in the TV's own libraries, which an app's linker namespace can't load, and a stale copy in `HiExtServices.jar` shadows it. The root helper loads it through `app_process` instead.
- **The HDMI viewer.** Android's hidden-API check blocks the HiSilicon methods, so Oriel adds `Lcom/hisilicon/` to `hidden_api_blacklist_exemptions`. The TV sizes the video window from the 1280x720 menu size, so the viewer sets it to 1920x1080 itself.
- **The phone mouse.** Android 12's `uinput` parser fails on configuration arrays longer than about 8 entries, so the virtual mouse declares only what it uses.
- **The Restore Factory boot loop.** While testing the system install, Restore Factory put every maker file back but read a settings backup taken while an earlier version of Oriel was installed. The maker's launcher stayed switched off and Oriel was uninstalled, so Android had no home screen and restarted in a loop with Wi-Fi never coming up. An earlier read of the recovery partition had concluded the TV had no recovery mode. That was wrong: holding the TV's power button while plugging it in opens Android Recovery, and Wipe data/factory reset brought it back. A firmware file published for the same board name was built for a different chip (Hi3751V352, Android 9, other memory sizes) and was never flashed. Restore Factory now always switches the maker's home back on, never uses settings from an earlier Oriel, and checks that the TV starts on the maker's home before it removes the watchdog.
- **Windows mirroring.** Five problems, one after another:
  - The sink library would not load from an app's class loader (`UnsatisfiedLinkError` on `libandroidicu.so`). The receiver now runs with Oriel on `BOOTCLASSPATH`.
  - HDCP modes 3 and 0 crash inside `libhiwfd`; mode 1, no HDCP, works.
  - `dnsmasq` exited silently because it could not write its default pid file in `/var/run`. It now gets `--pid-file` in `/data/local/tmp`.
  - The engine floods standard output, so the receiver logs to `/data/local/tmp/oriel-wfd.log`.
  - After a restart the receiver did not start, because the start command's own text matched the `pgrep` check for whether it was already running. The class name is now split in the command so it can't match itself.

## Adapting it to another TV

Everything here was found on this TV and checked only on it.

### What is specific to this TV

| Area | What Oriel assumes |
| --- | --- |
| Board maker | Hikeen. Home package `com.zeasn.launcher` for the launcher allow-list; maker homes `com.zeasn.hikeen` and `cn.zeasn.overseas.tv.hikeen.hisi352`; maker panels `com.hikeen.tvsettings/.menu.HotkeyService` and `com.hikeen.tv.factorymenu/.services.PowerMenuService`; the batches of maker apps in `installer/oriel.ps1` |
| Remote buttons | Hikeen key codes: Source 2001, Sleep 223, picture 2010, read from `PhoneWindowManagerEx` log lines |
| Chip libraries | HiSilicon `HitvManager` (picture and sound), `TvSourceManager` with `setSourceHolder("HiTvPlayer")` (HDMI and AV), `/system/lib/libsink_jni.so` with `libhiwfd.so` and `libhihdcp_adp.so` (Windows mirroring), HDCP modes from `/etc/miracast/hdcp.xml` |
| Wi-Fi Direct | `/vendor/bin/wpa_cli`, sockets in `/data/vendor/wifi/wpa/sockets`, interface `p2p0`, a driver that allows a group owner beside the normal connection on one channel, `/system/bin/dnsmasq` |
| Display | 1920x1080 panel, menus at 1280x720 on Mali-400 or Mali-450 or under 1.5 GB of RAM |
| System | Android 12 `userdebug`, 32-bit only, system-as-root ext4, no dm-verity or AVB, no dynamic partitions, `/` remountable read-write as root, about 370 MB free on system before Oriel |
| Boot screens | The HiSilicon logo layout in the `logo` partition, and a boot animation and video kept where the TV can write them |
| Root and access | `su` at `/system/xbin/su`, network ADB on port 5555 that survives restarts |
| Recovery | Android Recovery by holding the TV's power button while plugging it in |

The standard build (`com.muxover.oriel`) leaves out the Hikeen package name; the installer picks the build by checking for `com.hikeen` packages.

### A safe order

1. Read-only checks: `getprop`, `pm list packages -f`, `dumpsys`, `ls` of the paths above, `/proc/cmdline`. Note what differs.
2. Find the TV's recovery path and try it before anything touches the system. Without network access, a TV in a boot loop can only be saved from its own buttons or a cable.
3. Copy every partition to a PC and check the copies against checksums read on the TV.
4. Make one change at a time, each followed by a restart behind the watchdog, and check the TV after each.
5. Never flash firmware made for a board with a similar name. Check the chip, Android version and memory size inside the file first.

## Privacy

Oriel has no accounts and no analytics. The TV makes these connections, and no others of its own:

- Open-Meteo, for the weather and the city search, once a city is set.
- ESPN's public scoreboards for the leagues you follow, at most every two minutes, and ESPN's team lists once every two weeks when you search for a team.
- Google Play's app pages, once per app, for its icon.
- Cloudflare's speed test, only when you run the speed test.

Prayer times are worked out on the TV. The installer downloads adb from Google once and keeps the TV's backup on the PC.

The phone remote has no password: anyone on the same Wi-Fi can open it and control the TV. It's on by default and can be turned off in Settings > Remote and keyboard. See [SECURITY.md](../SECURITY.md).

## How it's built

- Licensed GPL-3.0.
- `home/`: Oriel, in Java with plain Android Views and no UI library. Minimum Android 10 (API 29), targeting API 28 on purpose: its Settings joins Wi-Fi networks and switches Wi-Fi and Bluetooth, which apps targeting API 29 or newer can no longer do on Android TV without a system signature. Two builds: `com.muxover.oriel` and `com.zeasn.launcher` for Hikeen boards.
- `mirror/`: Oriel Mirror, in Kotlin with Jetpack Compose over a native AirPlay receiver. It's a fork of [android-airplay-server](https://github.com/jqssun/android-airplay-server) by jqssun, built on [UxPlay](https://github.com/FDH2/UxPlay), with FFmpeg, OpenSSL and libplist as git submodules.
- `installer/`: `oriel.ps1` in PowerShell, started by the four `.bat` files. It uses adb over the network, so nothing is installed on the PC beyond Google's platform tools.
- `tools/package.sh`: builds both apps, signs them with a key kept outside the repo, draws the boot screens and packs `dist/oriel-<version>.zip`.

## Releases

There is one release, [v1.0.0](https://github.com/muxover/oriel/releases/tag/v1.0.0). Oriel isn't maintained: it changes only when I need something new on my own TV. What it contains is in the [CHANGELOG](../CHANGELOG.md).

## Known limits

- Android phones can't mirror to Oriel, and there's no Google Cast receiver.
- Whether Netflix, Prime Video and similar apps play, and in what quality, depends on the TV's own certification. This TV is not Netflix-certified.
- Everything that touches the chip, the remote's buttons or the system was tested only on this TV.
- The installer runs on Windows only.

## Issues

Oriel isn't supported, but notes from people adapting it are welcome as issues. Security reports go as [SECURITY.md](../SECURITY.md) describes.
