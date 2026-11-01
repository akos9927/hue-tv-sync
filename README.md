# Hue TV Sync

An Android TV app that syncs a Philips Hue gradient lightstrip with whatever is on the TV screen, streaming straight to the Hue Bridge with the Hue Entertainment API. No sync box, NAS or extra software needed.

**Install or update on the TV:** in the Downloader app, open
`github.com/akos9927/hue-tv-sync/releases/latest/download/HueTVSync.apk`

After the first install, use **Check for update** inside the app.

## What it does

- Records the screen at a small size (32, 64 or 128 px wide) and a chosen rate (5–30 fps).
- Averages the colors behind each strip segment, using the segment positions from your Hue entertainment area.
- Ignores letterbox bars, so the top segments follow the film rather than black.
- Streams to the bridge 50 times a second over DTLS, with adjustable smoothing, light delay, brightness, saturation and gamma.

Copy-protected streams (Netflix and similar) can't be captured and show up as black.

Builds are made by GitHub Actions on every push to `main` and published as releases.
