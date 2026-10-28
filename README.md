# Oriel

<div align="center">

[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/muxover/oriel)](https://github.com/muxover/oriel/releases/latest)

**A new home, mirroring and media system for my Android TV, built with Claude in three nights.**

</div>

> **Warning.** Oriel was made for one TV: a rooted VIEW ML43VIEWA620 on a HiSilicon Hi3751V553 board. It is not a product and it is not supported. It changes the TV's system partition, and on this TV one mistake already left it in a boot loop that only a factory reset fixed. Run it on another TV only if you can recover that TV without network access, and at your own risk.

---

## Why I made this

For a few weeks my feed was full of people vibe coding their TVs with Claude, mostly new home screens. My TV is a VIEW Android TV with 1 GB of RAM, and it was laggy from the day I got it: the maker's home screen was slow, its own screen mirroring was laggy and took the TV off Wi-Fi while it waited for a PC, and AirPlay wasn't there at all. So I tried it too.

Over three nights Oriel replaced the maker's home screen, settings and keyboard, added one mirror app for iPhone, Mac and Windows, put my team's live match on the screen saver, gave music its own Now Playing screen, and in the end moved all of it into the TV's own system. That last part once left the TV restarting in a loop, which is why every system change now goes through a watchdog that puts things back on its own. The story is on [my blog](https://muxover.com/blog/i-rebuilt-my-tv-with-claude-in-three-nights), and how every part works is in [docs/PROJECT.md](docs/PROJECT.md).

It's open source so your Claude can read it and fit it to your TV. It isn't a product and it isn't maintained: there is one release, and I'll only come back to it if I need something new on my own TV. [For an AI adapting this to another TV](#for-an-ai-adapting-this-to-another-tv) lists what is specific to this one.

---

## The TV

| | |
| --- | --- |
| Model | VIEW ML43VIEWA620 (November 2023), 1920x1080 panel |
| Board | S.T.CS2352P638, HiSilicon Hi3751V553, 4 Cortex-A53 cores running 32-bit |
| Memory | 1 GB RAM, 8 GB storage |
| Graphics | Mali-450 |
| Android | 12, `userdebug` build with dev-keys, from the board maker Hikeen |
| Access | network ADB on port 5555 from the factory, and `su` from the shell |
| Wi-Fi | 2.4 GHz only |

It is not Netflix-certified, so the Netflix TV app is not offered on it. That is outside anything Oriel can change.

---

## What it does

| Feature | What you get |
| --- | --- |
| Home screen | Its own home screen, light and fast on a 1 GB TV. Your apps in rows, and every app opens behind Oriel's launch screen. |
| Oriel Mirror | One place to mirror from iPhone, iPad, Mac and Windows: AirPlay, or Win + K on a PC. The TV stays on your Wi-Fi. |
| Screen saver | Your team's match, live, while the TV is idle, with the weather and the next prayer. |
| Now Playing | Music from any app, or sent from your iPhone, gets a screen of its own in the cover's colours. Go home and it keeps playing. |
| Phone remote | Your phone is the remote: arrows, a real mouse, typing into any app, and links and files sent to the TV. |
| Player | Oriel's own player for films with English or Arabic subtitles, music with its cover art, and photos, from the TV or a USB drive. |
| The Oriel keyboard | English, Arabic and symbols, in every app. |
| Make it yours | Six accent colours, and your own city and teams for the weather, prayer times and football. |
| Night mode | Warms and dims the picture over every app. |
| HDMI and AV | Its own input screen, on the remote's Source button. |
| Built into the system | Oriel lives inside the TV. If a change ever stops it booting, a watchdog rolls it back, and Restore Factory takes it all out in one go. |

How each part works, and every problem on the way, is in [docs/PROJECT.md](docs/PROJECT.md).

---

## For an AI adapting this to another TV

Treat everything below as an assumption about this TV, and check each one on yours before using the code that depends on it. [docs/PROJECT.md](docs/PROJECT.md) has the paths and details.

- **Board maker.** The Hikeen build, the package `com.zeasn.launcher`, the maker home packages `com.zeasn.hikeen` and `cn.zeasn.overseas.tv.hikeen.hisi352`, the remote hotkey services and the list of maker apps taken out of the system are all Hikeen's.
- **Chip libraries.** Picture settings, the HDMI viewer and Windows mirroring call HiSilicon code: `HitvManager`, `TvSourceManager` and `/system/lib/libsink_jni.so` with `libhiwfd.so` and `libhihdcp_adp.so`.
- **Wi-Fi Direct.** `wpa_cli` at `/vendor/bin/wpa_cli`, sockets in `/data/vendor/wifi/wpa/sockets`, interface `p2p0`, and a driver that allows a group owner next to the normal connection on one channel.
- **Display.** The 1280x720 menu size is chosen for a Mali-400 or Mali-450 chip or under 1.5 GB of RAM.
- **System.** System-as-root ext4 with no dm-verity and no dynamic partitions, writable after a remount as root, and a boot logo partition in the HiSilicon layout.
- **Recovery.** The way into Android Recovery here is the TV's power button held while plugging in. Find yours first.

The safe order: read-only checks first (`getprop`, `pm list packages`, `dumpsys`, `ls`). Then a full backup of every partition to a PC, and a recovery path you have tried. Then one change at a time, each followed by a restart behind the watchdog. Never flash firmware made for a board with a similar name.

---

## Installation

These steps are for the TV above.

1. On the TV, turn on Developer options, then Network debugging. Find the TV's IP address in its network settings.
2. Download `oriel-<version>.zip` from the [latest release](https://github.com/muxover/oriel/releases/latest) and unzip it on a Windows PC.
3. Run `Install Oriel.bat`. The first run downloads Android's platform tools (adb) from Google into a `tools` folder beside it.
4. Type the TV's IP address, then allow the connection on the TV and tick Always allow.

The installer reads the TV, saves its settings to `backups\<tv>\before-oriel.json`, installs Oriel and Oriel Mirror, and sets Oriel as the home screen, keyboard, accessibility service and notification listener. With root it also saves the original boot screens before replacing them.

### Building Oriel into the system

`Install Oriel System.bat` does everything above, then copies every partition except your data to `backups\<tv>\partitions` (about 2 GB, checked against checksums read on the TV), and builds Oriel into the system: Oriel becomes a privileged system app, the maker's apps leave the system on Hikeen boards, and Android's own dialogs get Oriel's look and the Geist font.

Each step goes through a boot watchdog on the TV. If Android starts and stays up, the step is kept. If it does not start within five minutes, the watchdog undoes the step and restarts the TV.

`Restore Factory.bat` puts the system back as the maker shipped it, removes Oriel, restarts the TV and checks it starts on the maker's home screen. If a TV ever stays on its loading animation, unplug it, hold the power button on the TV itself and plug it back in. On this board that opens Android Recovery, where Wipe data/factory reset brings the TV back.

### Removing Oriel

`Remove Oriel.bat` reads the backup, puts back the home screen, keyboard, accessibility services, screen size and density, animation speeds, TV name and boot screens, switches the maker's home back on, uninstalls Oriel and Oriel Mirror, and restarts the TV. Keep the folder: without its backup there is nothing to restore from.

---

## Building

```bash
git clone --recurse-submodules https://github.com/muxover/oriel.git
cd oriel
tools/package.sh
```

`tools/package.sh` builds both apps (the standard and Hikeen builds of the home app, and Oriel Mirror), signs them, draws the boot screens and puts the installer folder together as `dist/oriel-<version>.zip`. It needs the Android SDK with NDK 27.0.12077973, Python 3 with Pillow, ffmpeg and zip.

| Variable | Required | What it does |
|----------|----------|--------------|
| `ANDROID_HOME` | Yes | Path to the Android SDK |
| `ORIEL_KEYSTORE` | Yes | The release keystore, kept outside the repo |
| `ORIEL_KEYPASS_FILE` | Yes | A file holding the keystore's password |
| `ORIEL_ABIS` | No | Comma-separated ABIs for Oriel Mirror, for example `armeabi-v7a` for a quick build; all four by default |
| `ORIEL_WORKERS` | No | Gradle workers, 2 by default |

---

## Privacy

- No accounts and no analytics.
- The TV connects to Open-Meteo for the weather and the city search once a city is set, to ESPN's public scoreboards and team lists for football, to each app's Google Play page once for its icon, and to Cloudflare's speed test only when you run it.
- Prayer times are worked out on the TV.
- The phone remote has no password. Anyone on the same Wi-Fi can open it and control the TV. It can be turned off in Settings > Remote and keyboard. See [SECURITY.md](SECURITY.md).

---

## Limitations

- Android phones cannot mirror to Oriel, and there is no Google Cast receiver.
- Windows mirroring uses 1024x768 when the PC duplicates its screen. Win + P, Second screen only, gives 1080p.
- Whether Netflix, Prime Video and similar apps play, and in what quality, depends on the TV's certification.
- Everything that touches the chip, the remote's buttons or the system was tested only on this TV.
- The installer runs on Windows only.

---

## Project Layout

```text
oriel/
├── home/                     # Oriel, the home screen app (Java, plain Views)
│   └── src/main/
│       ├── java/             # Home, settings, keyboard, phone remote, media, mirroring and system screens
│       └── res/raw/remote.html  # The phone remote page
├── mirror/                   # Oriel Mirror, the AirPlay receiver (Kotlin and Compose over UxPlay)
│   └── src/main/cpp/         # Native receiver; UxPlay, FFmpeg, OpenSSL and libplist as submodules
├── installer/
│   ├── Install Oriel.bat     # Installs Oriel on a TV
│   ├── Remove Oriel.bat      # Puts the TV back from its backup
│   ├── Install Oriel System.bat  # Builds Oriel into a rooted TV's system
│   ├── Restore Factory.bat   # Puts the system back, then removes Oriel
│   ├── system/               # Boot watchdog, its helpers, and Oriel's system permissions
│   ├── oriel.ps1             # The installer and remover behind the .bat files
│   ├── tvkeeper.sh           # Root keeper: mouse mode off, Mirror running, menu size after boot
│   └── boot/make_boot.py     # Draws the boot logo, animation and video
├── system-overlay/           # Oriel's look for Android's own dialogs (a framework overlay)
├── tools/package.sh          # Builds, signs and packs a release zip
├── release-notes/            # The release notes
└── docs/PROJECT.md           # Project page
```

---

## Credits

Oriel Mirror is a fork of [android-airplay-server](https://github.com/jqssun/android-airplay-server) by jqssun, which is built on [UxPlay](https://github.com/FDH2/UxPlay). Both are GPL-3.0. It also uses [FFmpeg](https://github.com/FFmpeg/FFmpeg), [OpenSSL](https://github.com/viaduck/openssl-cmake) and [libplist](https://github.com/libimobiledevice/libplist). The interface uses the Geist and Geist Mono fonts, under the SIL Open Font License ([GEIST-OFL.txt](GEIST-OFL.txt)).

---

## License

Licensed under the [GPL-3.0](LICENSE) license.

---

## Links

- Repository: https://github.com/muxover/oriel
- Issues: https://github.com/muxover/oriel/issues
- Changelog: [CHANGELOG.md](CHANGELOG.md)

---

<p align="center">Made with ❤️ by Jax (@muxover)</p>
