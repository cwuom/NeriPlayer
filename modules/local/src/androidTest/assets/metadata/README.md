The audio fixture is synthetic noise generated for local metadata round-trip tests. It contains no recorded or third-party audio and has no artwork

Generation command:

```sh
ffmpeg -hide_banner -loglevel error -f lavfi -i 'anoisesrc=sample_rate=96000:amplitude=0.1:duration=0.25:seed=42' -ac 2 -c:a flac -sample_fmt s32 -metadata title=Original -metadata artist=Artist -metadata album=Fixture -metadata lyrics='[00:00.00]original' synthetic-edit.flac
```
