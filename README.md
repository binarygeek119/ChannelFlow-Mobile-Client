<h1 align="center">ChannelFlow Mobile</h1>
<h3 align="center">Android phone client for <a href="https://github.com/binarygeek119/ChannelFlow">ChannelFlow</a></h3>

---

<p align="center">
<img alt="ChannelFlow" src="logo.png" width="220"/>
</p>

ChannelFlow Mobile is the phone and tablet app for watching live IPTV from a [ChannelFlow](https://github.com/binarygeek119/ChannelFlow) server. It opens to the live guide, plays M3U streams, and pairs with a server using a quick pin. There is no Jellyfin login and no DVR.

It is a fork of [Jellyfin for Android](https://github.com/jellyfin/jellyfin-android), rebuilt around the same ChannelFlow pairing, guide, API key, and log-shipping behavior as [ChannelFlow TV](https://github.com/binarygeek119/ChannelFlow-Client).

Author: [binarygeek119](https://github.com/binarygeek119)

## Features

- Live TV guide loaded from the ChannelFlow M3U playlist and XMLTV listings
- Direct playback of live MPEG-TS / HLS streams in ExoPlayer
- Quick pin pairing (no server URL to type)
- Multiple saved servers, with switch / add / remove in settings
- Channel favorites and program reminders
- Unique per-device API key; the app drops a server if that key is revoked
- Client logs shipped to ChannelFlow-Server after pairing
- In-app updates from GitHub Releases

## Pairing

1. Open ChannelFlow on your phone and note the pin shown on screen.
2. In ChannelFlow-Server, open **Quick Pin** and enter that code.
3. The phone saves the server and opens the guide.

The pin relay is `https://channelflow.duckdns.org` and is not user-configurable. Pins last 10 minutes.

## Building

The app uses Gradle and needs the Android SDK. For a command-line debug build, use JDK 21 and the Gradle wrapper:

```shell
./gradlew assembleProprietaryDebug
```

The debug APK is written to `app/build/outputs/apk/proprietary/debug/` as `ChannelFlow-v<version>-debug.apk`. Debug builds use a `.debug` application id, so they can sit next to a release install.

A local release APK:

```shell
./gradlew assembleProprietaryRelease
```

The package id is `org.channelflow.mobile`.

## Releases

In-app updates look for GitHub Releases on [`binarygeek119/ChannelFlow-Mobile-Client`](https://github.com/binarygeek119/ChannelFlow-Mobile-Client).

Pushing a `v*` tag (or running **App / Release APK** from GitHub Actions) builds the signed proprietary release APK and attaches `ChannelFlow-vX.Y.Z-release.apk` to the GitHub Release. Settings can download and install that APK.

Release APKs are signed with `keystore/channelflow-release.jks` so updates install over each other. Optional repository secrets for a different production key: `KEYSTORE` (base64 of the `.jks` file), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
