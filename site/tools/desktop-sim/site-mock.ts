import { mockIPC, mockWindows } from '@tauri-apps/api/mocks'

mockWindows('main')
const w = window as any
w.__TAURI_INTERNALS__.metadata = { currentWindow: { label: 'main' }, currentWebview: { windowLabel: 'main', label: 'main' } }
w.__SITE_MOCK_LOG = []
w.__TAURI_INTERNALS__.convertFileSrc = (p: string) => p

const COVER = (k: string) => `/__covers/${k}.jpg`
const EN = new URLSearchParams(location.search).get('lang') === 'en'
const SONGS: [string, string, string, string, number][] = [
  ['startrail', EN ? 'Startrail' : '星轨', 'Neri Line', 'Startrail', 66000],
  ['two-am', '02:00 AM', 'Lumen Drift feat. Soda Cat', 'Night Shift', 214000],
  ['polaris', 'Polaris', 'Northbound', 'Northbound', 241000],
  ['paper-planets', 'Paper Planets', 'Velvet Orbit', 'Orbit Diary', 198000],
  ['seaside-signal', EN ? 'Seaside Signal' : '海边的信号', 'Glass Harbor', 'Harbor Lights', 226000],
  ['daylight-route', EN ? 'Daylight Route' : '白昼航线', 'Sora Atlas', 'Atlas', 203000],
  ['cat-soda', EN ? 'Cat & Soda' : '猫与汽水', 'Soda Cat', 'Fizz', 187000],
  ['low-orbit', 'Low Orbit', 'Mellow Circuit', 'Circuit', 232000],
  ['rain-radio', EN ? 'After-Rain Radio' : '雨后电台', 'Paper Satellite', 'Satellite FM', 219000],
  ['goodnight-train', EN ? 'Goodnight, Train' : '晚安，列车', 'Neri Line', 'Startrail', 245000],
]
const neteaseSong = (s: typeof SONGS[number], i: number) => ({
  id: 1000 + i, name: s[1], ar: s[2].split(' feat. ').map(name => ({ name })), al: { name: s[3], picUrl: COVER(s[0]) }, dt: s[4],
})
const rot = (n: number) => SONGS.slice(n).concat(SONGS.slice(0, n))
const PL_NAMES = EN
  ? ['Late-night commute', 'Rainy window', 'Lo-fi for focus', 'City pop drive', 'Starlit train', 'Weekend morning', 'Galgame OST', 'Soft piano']
  : ['深夜通勤', '雨天的窗边', '专注 Lo-fi', 'City Pop 兜风', '星空列车', '周末清晨', 'Galgame 原声', '轻钢琴']
const playlists = (n: number) => PL_NAMES.map((name, i) => ({
  id: 5000 + i + n * 10, name, picUrl: COVER(rot(n)[i % 10][0]), trackCount: 20 + i * 7, playCount: 120000 + i * 53211,
}))

const START = SONGS[0]
w.__SITE_TRACK = {
  id: 'local:startrail', title: START[1], artist: START[2], album: START[3], durationMs: START[4],
  coverUrl: COVER('startrail'), audioUrl: 'C:/Music/Neri Line - Startrail.flac', source: 'local',
}
w.__SITE_QUEUE = SONGS.map((s, i) => ({
  id: i ? `local:${s[0]}` : 'local:startrail', title: s[1], artist: s[2], album: s[3], durationMs: s[4],
  coverUrl: COVER(s[0]), audioUrl: `C:/Music/${s[0]}.flac`, source: 'local',
}))

const RAW: [number, number, string[], number[], string][] = [[7.5,11.016,["把","喜","欢","的","歌 ","留","在","掌","心"],[7.734,7.969,8.203,8.438,9.375,9.609,9.844,10.078],"Keep the songs you love close at hand"],[11.25,14.531,["让","歌","词 ","随","声","音","展","开"],[11.484,11.719,12.422,12.656,12.891,13.125,13.594],"And let the lyrics unfold with the sound"],[15,16.641,["音","理 ","音","理！"],[15.352,15.703,15.938],"Neri, Neri!"],[18.75,21.562,["无","论","从","哪","里","来","的","歌"],[18.984,19.219,19.453,19.688,19.922,20.156,20.391],"Wherever the song may come from"],[22.5,25.781,["换","一","条","轨 ","也","不","会","停"],[22.734,22.969,23.203,23.906,24.141,24.375,24.609],"Switch the track, and it never stops"],[26.25,29.766,["每","个","字 ","都","落","在","节","拍","上"],[26.484,26.719,27.422,27.656,27.891,28.125,28.594,29.062],"Every word lands right on the beat"],[30,33.516,["原","文","与","译","文 ","一","同","亮","起"],[30.234,30.469,30.703,30.938,31.641,31.875,32.109,32.578],"Original and translation light up as one"],[33.75,37.266,["每","一","个","采","样 ","原","样","抵","达"],[33.984,34.219,34.453,34.688,35.625,35.859,36.094,36.328],"Every sample arrives untouched"],[37.5,40.312,["不","多","一","分 ","也","不","少","一","分"],[37.734,37.969,38.203,38.672,38.906,39.141,39.375,39.609],"Nothing added, nothing lost"],[41.25,44.531,["连","接","断","开 ","歌","还","在","继","续"],[41.484,41.719,41.953,42.656,42.891,43.125,43.359,43.594],"The signal drops, the song plays on"],[45,48.281,["回","来","的","路 ","一","直","都","在"],[45.234,45.469,45.703,46.406,46.641,46.875,47.109],"The way back is always there"],[48.75,52.031,["隔","着","很","远 ","听","同","一","首","歌"],[48.984,49.219,49.453,50.156,50.391,50.625,50.859,51.094],"Miles apart, on the very same song"],[52.5,55.781,["同","一","句","歌","词 ","同","一","秒"],[52.734,52.969,53.203,53.438,54.375,54.609,54.844],"Same line, same second"],[60,61.875,["音","理 ","音","理"],[60.352,60.703,60.938],"Neri, Neri"]]
const LYRICS = {
  source: 'amll_ttml',
  lines: RAW.map(r => {
    const starts = [r[0], ...r[3]]
    return {
      startMs: Math.round(r[0] * 1000), durationMs: Math.round((r[1] - r[0]) * 1000), text: r[2].join(''), translation: r[4],
      words: r[2].map((t, i) => ({ startMs: Math.round(starts[i] * 1000), durationMs: Math.round(((i + 1 < starts.length ? starts[i + 1] : r[1]) - starts[i]) * 1000), text: t })),
    }
  }),
}

const homeSection = ({ source }: { source: string }) => {
  const n = source.length % 10
  if (['personal_radar', 'top_soaring', 'personalized_new_songs', 'top_hot', 'top_new'].includes(source))
    return { code: 200, data: { songs: rot(n).map(neteaseSong) }, result: rot(n).map(neteaseSong) }
  return { code: 200, result: playlists(n) }
}

const now = Date.now()
const localPlaylists = [
  { id: 1, name: EN ? 'Liked Songs' : '我喜欢的音乐', track_count: 128, modified_at: now, cover_url: COVER('startrail') },
  { id: 2, name: EN ? 'Night drive' : '夜间行车', track_count: 42, modified_at: now, cover_url: COVER('two-am') },
  { id: 3, name: EN ? 'Rainy afternoon' : '下雨的午后', track_count: 36, modified_at: now, cover_url: COVER('rain-radio') },
  { id: 4, name: 'Neri Line', track_count: 18, modified_at: now, cover_url: COVER('goodnight-train') },
  { id: 5, name: EN ? 'Focus' : '专注', track_count: 64, modified_at: now, cover_url: COVER('low-orbit') },
  { id: 6, name: 'City Pop', track_count: 51, modified_at: now, cover_url: COVER('daylight-route') },
]

const handlers: Record<string, unknown> = {
  get_netease_home_section: homeSection,
  get_playlist_usage_stats: localPlaylists.slice(1).map((p, i) => ({ id: String(p.id), source: 'local', name: p.name, coverUrl: p.cover_url, trackCount: p.track_count, lastOpenedAt: now - i * 3600e3, openCount: 20 - i })),
  get_home_local_playlists: localPlaylists.slice(1),
  list_playlists: localPlaylists,
  local_library_snapshot: {
    folders: [{ path: 'C:/Music', trackCount: 10 }], scanning: false, skipped: [],
    tracks: SONGS.map((s, i) => ({ id: `local:${s[0]}`, title: s[1], artist: s[2], album: s[3], duration_ms: s[4], cover_url: COVER(s[0]), url: `C:/Music/${s[0]}.flac`, source: 'local', added_at: now - i * 864e5 })),
  },
  fetch_lyrics: LYRICS,
  fetch_word_timed_lyrics: LYRICS,
  play_file: () => START[4],
  get_player_state: () => ({ is_playing: true, position_ms: 0, duration_ms: START[4] }),
  get_local_audio_info: { codec: 'FLAC', sampleRate: 48000, bitDepth: 24, channels: 2, bitrate: 1411 },
  get_playback_audio_info: { codec: 'FLAC', sampleRate: 48000, bitDepth: 24, channels: 2, bitrate: 1411 },
  get_build_info: { version: 'b925407.10131832', buildTime: '2026-10-13 18:32', uuid: 'preview' },
  get_liked_song_ids: [],
  list_audio_output_devices: [{ id: 'default', name: 'Speakers (Realtek(R) Audio)', isDefault: true }],
  get_audio_effects_stats: { active: true, cpuLoadPercent: 1.8, limiterReductionDb: 0, compressorReductionDb: 0.6, sampleRate: 48000, nativeAvailable: true, inactiveReason: null, outputDevice: 'Speakers (Realtek(R) Audio)' },
}
w.__SITE_MOCK = handlers

mockIPC((cmd, args) => {
  w.__SITE_MOCK_LOG.push(cmd)
  if (cmd in w.__SITE_MOCK) {
    const v = w.__SITE_MOCK[cmd]
    return typeof v === 'function' ? v(args) : v
  }
  if (cmd.startsWith('plugin:window|') || cmd.startsWith('plugin:webview|')) {
    if (cmd.endsWith('is_maximized') || cmd.endsWith('is_fullscreen') || cmd.endsWith('is_minimized')) return false
    if (cmd.endsWith('is_focused') || cmd.endsWith('is_visible')) return true
    if (cmd.endsWith('scale_factor')) return 1
    if (cmd.endsWith('inner_size') || cmd.endsWith('outer_size')) return { width: innerWidth, height: innerHeight }
    if (cmd.endsWith('theme')) return 'light'
    return null
  }
  if (cmd.startsWith('plugin:')) return null
  if (cmd.startsWith('list_') || cmd.endsWith('_ids') || cmd === 'get_recent_logs') return []
  return null
}, { shouldMockEvents: true })
