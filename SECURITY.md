# Security policy

Lyre is a small, open-source Android music player maintained by one person in
their spare time. Security reports are welcome and taken seriously.

## Reporting a vulnerability

**Please don't open a public issue for security problems.**

Report privately through GitHub instead: go to the
[Security tab](https://github.com/Aevrin/lyre/security) and choose
**Report a vulnerability**. Only the maintainer can see the report.

Helpful to include:

- What the problem is and what an attacker could do with it.
- Steps to reproduce, or a proof of concept.
- The Lyre version (Settings → About) and your Android version and device.

## What to expect

- An acknowledgement within about a week.
- An honest assessment of whether it's a real issue and how serious it is.
- For confirmed issues, a fix in the next release, published through Google
  Play and noted in the commit history. You'll be credited unless you'd rather
  not be.

This is a volunteer project, so there is no bug bounty.

## Supported versions

Only the latest release receives fixes. Updates ship through Google Play, so
the current version there is the supported one.

## Scope

Lyre deliberately has a small attack surface:

- It does **not** request the internet permission, so it cannot send or
  receive anything over the network.
- It collects no data (see the [privacy policy](PRIVACY.md)).
- Its only other permissions are reading audio files and background playback.
  It changes music files only when you save tags, after Android's own
  confirmation dialog.

Things that are in scope include, for example: another app being able to
control playback beyond what Android's media session allows, change Lyre's
settings, playlists or blacklist, or read its data without the user's action;
crashes or hangs triggered by malformed audio files, tags or M3U playlists;
and tag edits corrupting files.

Out of scope: problems in Android itself or its codecs, and anything that
needs a rooted or already-compromised device.
