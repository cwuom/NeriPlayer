# Synthetic playback metadata fixture

`synthetic-playing-restore.flac` contains 30 seconds of generated mono silence,
original title/artist and three independent lyric tracks, with no artwork.
It contains no third-party audio or lyrics.

Regenerate from the repository root with FFmpeg:

```sh
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i anullsrc=r=16000:cl=mono -t 30 \
  -c:a flac -sample_fmt s16 -map_metadata -1 \
  -metadata title='Original title' \
  -metadata artist='Original artist' \
  -metadata album='Synthetic restore fixture' \
  -metadata 'LYRICS=[00:01.00]original lyric' \
  -metadata 'NERI_LYRICS_ORIGINAL=[00:01.00]original lyric' \
  -metadata 'LYRICS_TRANSLATED=[00:01.00]original translation' \
  -metadata 'NERI_LYRICS_ROMANIZED=[00:01.00]original romanization' \
  app/src/androidTest/assets/metadata/synthetic-playing-restore.flac
```

`RestoredLocalPlaybackThreadTest.singleRestoreWhilePlayingPublishesOriginalMetadataToSessionAndNotification`
runs only on an API 37 emulator with notifications allowed and no existing
playback, playback service, sleep timer or sync target. It uses a private copy,
restores the saved queue and runtime preferences, and removes only its synthetic
history/statistics entries and temporary media files.
