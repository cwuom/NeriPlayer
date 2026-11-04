# Synthetic Hi-Res container diagnostics

The three `synthetic-hires-*` audio files contain one second of locally generated
96 kHz, 24-bit stereo silence. They contain no third-party audio, artwork, lyrics,
account data or credentials. `DownloadedFlacStagingRegressionTest` creates its
cover and lyric text locally and uses a private cache directory for each case.

Generate the raw FLAC fixture from the repository root:

```sh
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i anullsrc=r=96000:cl=stereo -t 1 -map_metadata -1 \
  -c:a flac -sample_fmt s32 -bits_per_raw_sample 24 \
  -fflags +bitexact -flags:a +bitexact \
  app/src/androidTest/assets/metadata/synthetic-hires-raw.flac
```

For `synthetic-hires-mp4.m4a`, use the same command with `-strict -2 -f mp4`
before the output path. For `synthetic-hires-fragmented.m4a`, additionally use
`-movflags empty_moov+frag_keyframe+default_base_moof` before `-f mp4`.

The diagnostic class is marked `ManualInstrumentedTest` and covers formal and
pending download names for all three containers. It calls the actual download
tag writer and verifies native tag/cover readback, channel count, unchanged
reported audio format, codec configuration, extracted samples and timestamps.
Raw FLAC additionally checks the encoded frames after its metadata blocks.
A failed write must leave
the original file byte-for-byte intact.

Android's raw FLAC extractor may return decoded PCM (`audio/raw`). These MP4
fixtures have a 48 kHz `stsd` field and a 96 kHz FLAC STREAMINFO configuration;
the system extractor may report either field. The before/after format and audio
data must still match exactly. The fixture generation and independent byte audit
establish the encoded stream's 96 kHz, 24-bit, stereo configuration.

These generated files do not represent authenticated Bilibili responses. Results
from this class do not establish reproduction or resolution of issue #637.
