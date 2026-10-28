# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-10-28

The only release. Oriel isn't maintained; it changes only when I need something new on my own TV.

### Added

- Home screen with rows by kind of app (Watch, Listen, Sports, News and talks, Kids, Your media, Games, More apps) plus Connect and System. Only installed apps show, each with its official Google Play icon and a line from its Google Play page, and new installs land in the right row on their own.
- A launch screen behind every app, and a Now playing row on home while music plays.
- Screen saver with the clock, the weather from Open-Meteo, the next prayer and the live match of your teams from ESPN's public scoreboards (48 leagues and cups). Each part can be turned off.
- Prayer times with 9 calculation methods and Hanafi Asr, worked out on the TV.
- Now Playing for music from any app and from an iPhone over AirPlay, in the colours of the cover. Music apps keep playing when you go home.
- Settings in groups: Network with a speed test, Bluetooth, Screen mirroring, Name and place, Prayer times, Matches, Night mode, Screen saver, Appearance (six accent colours), Clock and date, Remote and keyboard, Picture and sound on HiSilicon boards, Apps, Storage and About.
- The Oriel keyboard in English, Arabic and symbols, in every app.
- Phone remote on port 8080: arrows, a virtual USB mouse on rooted TVs, typing into any app, and links and files sent to the TV.
- Night mode, Oriel's own volume popup, power screen and sleep timer, and the remote's Source, Sleep, picture and Power buttons on Hikeen boards.
- Oriel Mirror, an AirPlay receiver for iPhone, iPad and Mac, forked from jqssun's android-airplay-server.
- Windows mirroring with Win + K on rooted TVs, through the TV's own HiSilicon Miracast sink, in a Wi-Fi Direct group on the router's channel so the TV stays on Wi-Fi.
- Media: a grid of videos, music and photos from USB drives and the TV's storage, Continue watching, a video player with `.srt` subtitles (UTF-8 or Windows-1256) and track choice, a music player and a photo viewer with a slideshow.
- Oriel's own HDMI and AV viewer.
- Oriel boot logo, boot animation and boot video.
- Menus drawn at 720p on slow graphics chips, with video left at full resolution.
- Windows installer (`Install Oriel.bat`) over network ADB that backs up the TV's settings and boot screens first, and `Remove Oriel.bat` that puts them back.
- `Install Oriel System.bat`: on rooted TVs, copies every partition except the user's data to the PC, then builds Oriel into the system as a privileged app, takes the maker's apps out on Hikeen boards, and gives Android's own dialogs Oriel's look and the Geist font.
- A boot watchdog on the TV that undoes a system change by itself when Android does not start after it.
- `Restore Factory.bat`: puts the system back as the maker shipped it, removes Oriel and checks that the TV starts again.

[1.0.0]: https://github.com/muxover/oriel/releases/tag/v1.0.0
