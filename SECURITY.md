# Security Policy

## Reporting a Vulnerability

Do NOT open a public issue. Email contact@muxover.com with: description, steps to reproduce, impact. Response within 48 hours.

## Supported Versions

| Version | Supported |
|---------|-----------|
| latest  | Yes       |

## Known limitations

- The phone remote on port 8080 has no password. Anyone on the same Wi-Fi can open it, press keys, type, move the pointer, and send links, photos and videos to the TV. It's on by default; turn it off in Settings > Remote and keyboard on a network you don't trust.
- Oriel needs network debugging (ADB over the network) left on, because its helper runs through the TV's own ADB service. Any computer on the network can ask the TV for an ADB connection, and the TV shows a prompt for each new one. Allow only computers you know.
