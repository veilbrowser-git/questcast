# QuestCast

Cast a Meta Quest to any device. The headset shows a 6-digit code; type it
into the website on a phone, tablet, laptop or TV browser to watch — with
zoom, pan and full screen.

## How it fits together

| Piece | What it does |
| --- | --- |
| `quest-app/` | Android app for the headset. Captures the view and streams it. |
| `public/` | The website: `index.html` to watch, `cast.html` to share a computer screen. |
| `server.js` | Small relay that pairs a headset with viewers by code. Video goes straight from headset to viewer (WebRTC), not through the relay. |

## Run it

```bash
npm install
npm start
```

The relay prints its address, e.g. `http://192.168.1.20:8787`. Open that
address on any device on the same Wi-Fi.

## Put the app on the headset

1. Turn on developer mode for the headset (Meta Horizon phone app → headset settings).
2. Install `QuestCast.apk` (from this repo's Releases) with SideQuest, or over USB:
   ```bash
   adb install QuestCast.apk
   ```
3. In the headset open **Library → Unknown sources → QuestCast**.
4. Enter the relay address once (it is remembered), pick a quality, press **Start casting** and allow sharing.
5. Type the code shown into the website.

## Watching

- Scroll or pinch to zoom, drag to pan, double-tap to zoom in and back out.
- Toolbar: zoom, fill the screen, full screen, settings. Keys: `+` `-` `0` `F`.
- Settings: lower the frame rate (30/20/15/10 FPS) or picture quality for your own view, e.g. on a slow connection. The choice is remembered. The badge in the corner shows the resolution and FPS actually arriving.
- The headset app has its own frame-rate choice (30/20/15 FPS), which caps every viewer and is lighter on the headset.
- A link like `https://your-site/#123456` joins directly.

## Using it away from home

The relay is hosted at **https://questcast.onrender.com** (Render free plan,
set up from `render.yaml`), so casts work across networks:

- Website: <https://veilbrowser-git.github.io/questcast/>
- In the headset app, enter `questcast.onrender.com` as the cast server.

A free relay sleeps after 15 idle minutes and takes about a minute to wake;
the headset app and the website both wait for it. Some strict networks
(certain mobile carriers, offices, schools) block direct device-to-device
video; fixing that needs a TURN server, which is not set up.

To host your own relay instead, deploy this repo anywhere that runs Node with
HTTPS and change `HOSTED_RELAY` in `public/config.js`.

## Ads

The watch page has one ad space under the code box, for Google AdSense. It is
empty until `QUESTCAST_ADS` in `public/config.js` is filled in with the
publisher ID and ad-unit slot from an approved AdSense account. Add
`?ads=preview` to the address to see where it sits. Ads are never shown over
the video.

## Build the headset app

Needs a JDK 17 and the Android SDK (Android Studio works; so does the SDK that
ships with Unity's Android module).

```bash
cd quest-app
echo "sdk.dir=/path/to/Android/sdk" > local.properties
gradle assembleDebug
```

Add `-PdefaultServer=192.168.1.20:8787` to pre-fill the relay address.

## Good to know

- Anyone who has the code can watch until the cast stops. The headset shows how many people are watching.
- Sound is not sent from the headset yet (video only). Sharing a computer screen from `cast.html` includes sound where the browser offers it.
- Apps that block recording show as black, the same as with built-in casting.
