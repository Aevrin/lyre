# Lyre

A small, private, open-source music player for Android, in the spirit of Shuttle+.

> **Vibe-coded.** Lyre is written largely by an AI model (Claude, by Anthropic) under
> human direction, review and on-device testing. It is not the work of an experienced
> Android team. Read the code before trusting it, and expect rough edges.

- No ads, no analytics, no accounts, and **no internet permission**.
- Free software under the [GNU GPL v3 or later](LICENSE).
- Android 13+.

## Status: 0.2

### What it does

- **Library from Android's media scanner.** Lyre doesn't scan storage itself: it reads
  MediaStore, so the library is there instantly and updates when Android finds new files.
  Tabs for Songs, Albums, Artists (by album artist), Genres, Folders and Playlists, with
  search across songs, albums and artists. Tap the **Lyre** title to reload.
- **Blacklist you can switch off.** Hide songs, albums, artists, genres or folders (a folder
  includes its subfolders) from their ⋮ menu or Settings. *Settings → Blacklist → Use blacklist*
  turns it off and on without clearing it; entries can be removed one at a time.
- **Volume normalization**, so you don't keep reaching for the volume:
  - *Auto* (default) measures each track's loudness the way ReplayGain 2.0 does (EBU R128),
    once per file, cached. Upcoming tracks are measured in the background, or measure the
    whole library at once from Settings. ReplayGain tags are used until a track is measured.
  - *ReplayGain track / album* uses tags from your tagger, falling back to the measurement.
  - Target loudness, pre-amp and the gain for not-yet-measured tracks are adjustable.
  - Gain is applied with Android's DynamicsProcessing effect plus a limiter, so boosting
    quiet tracks can't clip. Now Playing shows the gain applied to the current track.
- **Tag editor.** Title, artist, album, album artist, genre, year, track, disc, composer,
  comment and cover art, for one song or many at once (an album, artist, ...). Artist and
  album artist suggest names already in your library after two letters. Android asks
  once per save for permission to modify the files; the media scanner then re-reads them.
  Uses jaudiotagger: MP3, FLAC, Ogg Vorbis, M4A/MP4, WMA, WAV, AIFF, DSF.
- **Playlists** with drag-to-reorder, **M3U export** (one playlist, or all of them into a
  folder) with relative or absolute paths, and **M3U import**.
- **Dynamic playlists** that update themselves from rules: title, artist, album, album artist,
  genre, folder, year, length, play count, skip count, last played and date added (e.g. "added
  more than 365 days ago", "played at least 5 times", "longer than 10 minutes"). Match all or
  any rule, pick an order (random, most played, newest added, ...) and an optional limit. Start
  blank or from a preset; save one as a regular playlist or export it as M3U.
  A play counts after half a track or 4 minutes of listening; a skip is pressing Next within
  the first 30 seconds.
- **Duplicate finder** (Settings → Library → Find duplicates): *same song* (title and artist
  ignoring case, punctuation and accents, lengths within 3 seconds, so an MP3 and a FLAC of one
  track match) or *identical files* (byte-for-byte). Each copy shows its full file location,
  format, bitrate and size; the suggested keeper (lossless, then highest bitrate) is marked.
  Remove all extras at once or pick by hand; files go to Android's trash (restorable for 30
  days) and playlists switch to the copy you kept.
- **Queue:** play next, add to queue, drag to reorder, swipe to remove, save as a playlist.
- Shuffle, repeat, sleep timer (minutes or end of track), gapless playback, lock-screen,
  notification, Bluetooth and headset controls (Media3), resume where you left off.
  External equalizer apps can attach to Lyre's audio.
- Dark theme by default (light or follow system in Settings).

### Permissions

| Permission | Why |
|---|---|
| Music and audio (`READ_MEDIA_AUDIO`) | Read the library from Android's media scanner. The one prompt. |
| Foreground service (media playback), wake lock | Keep playing with the screen off. Granted at install. |

Saving tags uses Android's per-file "Allow Lyre to modify…" dialog instead of "All files access".

## Release notes

Play's "What's new" text comes from `distribution/whatsnew/whatsnew-en-US` (500 characters
max), updated with each change.

## Building

CI (`.github/workflows/build.yml`) builds a debug APK and runs the unit tests on every push.
`play.yml` builds a signed bundle and publishes to Play testing tracks once its secrets and
variables are set (see the comments at the top of that file).

Locally: Gradle 9.5+, JDK 17+, Android SDK 36. `gradle :app:assembleDebug`.
For signed builds, copy `signing.properties.example` to `signing.properties`.

## Libraries

- [AndroidX Media3](https://github.com/androidx/media) (Apache-2.0): playback and media session.
- [AndroidX RecyclerView](https://developer.android.com/jetpack/androidx/releases/recyclerview) (Apache-2.0).
- [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) (LGPL-2.1): reading and writing tags.
