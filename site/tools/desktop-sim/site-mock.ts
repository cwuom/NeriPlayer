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
  { id: '1', name: EN ? 'Liked Songs' : '我喜欢的音乐', track_count: 10, modified_at: now, cover_url: COVER('startrail') },
  { id: '2', name: EN ? 'Night drive' : '夜间行车', track_count: 8, modified_at: now, cover_url: COVER('two-am') },
  { id: '3', name: EN ? 'Rainy afternoon' : '下雨的午后', track_count: 7, modified_at: now, cover_url: COVER('rain-radio') },
  { id: '4', name: 'Neri Line', track_count: 6, modified_at: now, cover_url: COVER('goodnight-train') },
  { id: '5', name: EN ? 'Focus' : '专注', track_count: 9, modified_at: now, cover_url: COVER('low-orbit') },
  { id: '6', name: 'City Pop', track_count: 8, modified_at: now, cover_url: COVER('daylight-route') },
]
const LOCAL_ORDER: Record<string, string[]> = {
  1: SONGS.map(s => s[0]),
  2: ['two-am', 'polaris', 'low-orbit', 'goodnight-train', 'startrail', 'paper-planets', 'seaside-signal', 'rain-radio'],
  3: ['rain-radio', 'cat-soda', 'daylight-route', 'paper-planets', 'two-am', 'seaside-signal', 'startrail'],
  4: ['startrail', 'goodnight-train', 'polaris', 'seaside-signal', 'two-am', 'low-orbit'],
  5: ['low-orbit', 'paper-planets', 'rain-radio', 'polaris', 'startrail', 'two-am', 'daylight-route', 'cat-soda', 'goodnight-train'],
  6: ['daylight-route', 'cat-soda', 'seaside-signal', 'startrail', 'paper-planets', 'two-am', 'polaris', 'rain-radio'],
}
const byKey = (k: string) => SONGS.find(s => s[0] === k) || START
const localTrack = (s: typeof SONGS[number], i: number) => ({
  id: s === START ? 'local:startrail' : `local:${s[0]}`, title: s[1], artist: s[2], album: s[3], duration_ms: s[4],
  cover_url: COVER(s[0]), url: `C:/Music/${s[0]}.flac`, source: 'local', added_at: now - i * 864e5,
})
const PL_DESC = EN
  ? 'Picked by the radar from what you have been playing lately — sample tracks from the NeriPlayer PV.'
  : '按最近常听的歌由雷达挑出来的歌单，曲目是 NeriPlayer PV 里的示例曲。'
// 歌单详情只认 http(s) 封面；快照脚本拦截这个域名，返回 public/__covers 里的同名文件
const NET_COVER = (k: string) => `https://covers.neri.invalid/__covers/${k}.jpg`
const neteasePlaylist = ({ playlistId }: { playlistId: number | string }) => {
  const id = Number(playlistId) || 5000
  const i = (id - 5000) % 10
  const n = Math.floor((id - 5000) / 10) % 10
  const fav = favoritePlaylists.find(f => f.id === String(id))
  const songs = rot(fav ? fav.rot : (n + i) % 10).map((s, k) => {
    const x = neteaseSong(s, k)
    return { ...x, al: { ...x.al, picUrl: NET_COVER(s[0]) } }
  })
  return {
    code: 200,
    playlist: {
      id, name: fav ? fav.name : PL_NAMES[i % PL_NAMES.length], coverImgUrl: NET_COVER(fav ? fav.cover : rot(n)[i % 10][0]), trackCount: songs.length,
      playCount: 120000 + i * 53211, description: PL_DESC, creator: { nickname: 'NeriPlayer' }, tracks: songs,
    },
  }
}
const favoritePlaylists = [
  { id: '5098', name: EN ? 'Starlit train' : '星空列车', source: 'netease', coverUrl: COVER('goodnight-train'), cover: 'goodnight-train', trackCount: 10, addedTime: now - 864e5, modifiedAt: now, sortOrder: 2, rot: 9, songs: rot(9).map(neteaseSong), tracks: rot(9).map(localTrack) },
  { id: '5099', name: EN ? 'Lo-fi for focus' : '专注 Lo-fi', source: 'netease', coverUrl: COVER('low-orbit'), cover: 'low-orbit', trackCount: 10, addedTime: now - 3 * 864e5, modifiedAt: now, sortOrder: 1, rot: 7, songs: rot(7).map(neteaseSong), tracks: rot(7).map(localTrack) },
]
/* 收藏里关注的歌手：网易云三位、哔哩哔哩一位（YouTube Music 留空，和没导入关注时一样） */
const ARTISTS: { id: string, source: string, name: string, cover: string, subtitle: string, songs: string[] }[] = [
  { id: '9001', source: 'neteaseArtist', name: 'Neri Line', cover: 'startrail', subtitle: EN ? '2 songs · 1 album' : '2 首歌 · 1 张专辑', songs: ['startrail', 'goodnight-train'] },
  { id: '9002', source: 'neteaseArtist', name: 'Soda Cat', cover: 'cat-soda', subtitle: EN ? '2 songs · 2 albums' : '2 首歌 · 2 张专辑', songs: ['cat-soda', 'two-am'] },
  { id: '9003', source: 'neteaseArtist', name: 'Glass Harbor', cover: 'seaside-signal', subtitle: EN ? '1 song · 1 album' : '1 首歌 · 1 张专辑', songs: ['seaside-signal'] },
  { id: '7001', source: 'biliArtist', name: EN ? 'Neri Line Official' : '音理线官方', cover: 'goodnight-train', subtitle: EN ? 'PV and live clips' : 'PV 与现场片段', songs: ['startrail', 'goodnight-train', 'polaris'] },
]
const artistFavorites = ARTISTS.map((a, i) => ({
  id: a.id, name: a.name, source: a.source, coverUrl: COVER(a.cover), subtitle: a.subtitle, trackCount: a.songs.length,
  addedTime: now - (i + 5) * 864e5, modifiedAt: now, sortOrder: -1 - i,
}))
const artistOf = (id: string | number) => ARTISTS.find(a => a.id === String(id)) || ARTISTS[0]
const netSong = (k: string, i: number) => {
  const s = byKey(k)
  const x = neteaseSong(s, SONGS.indexOf(s))
  return { ...x, al: { ...x.al, picUrl: NET_COVER(s[0]) }, _i: i }
}
const highQuality = ({ cat }: { cat?: string }) => {
  const n = String(cat || '').length % 10
  return {
    code: 200,
    playlists: PL_NAMES.map((name, i) => ({
      id: 5100 + n * 10 + i, name, coverImgUrl: NET_COVER(rot(n)[i % 10][0]), trackCount: 20 + i * 7,
      description: PL_DESC, creator: { nickname: 'NeriPlayer' },
    })),
  }
}
const statItem = (s: typeof SONGS[number], i: number, k: number) => ({
  identityKey: `local:${s[0]}`, id: `local:${s[0]}`, name: s[1], artist: s[2], album: s[3], albumId: '', coverUrl: COVER(s[0]),
  mediaUri: `C:/Music/${s[0]}.flac`, durationMs: s[4], playCount: Math.max(1, Math.round((10 - i) * 3.2 * k)),
  totalListenMs: Math.round((10 - i) * 3.2 * k * s[4] * 0.86), lastPlayedAt: now - i * 3600e3, firstPlayedAt: now - (20 + i) * 864e5,
})
const statsOverview = (['day', 'week', 'month', 'year', 'all'] as const).map((period, p) => {
  const k = [0.4, 1, 3.4, 18, 26][p]
  const items = SONGS.map((s, i) => statItem(s, i, k))
  return {
    period, trackCount: items.length, items,
    totalPlayCount: items.reduce((a, b) => a + b.playCount, 0), totalListenMs: items.reduce((a, b) => a + b.totalListenMs, 0),
  }
})

const handlers: Record<string, unknown> = {
  get_netease_home_section: homeSection,
  get_playlist_usage_stats: localPlaylists.slice(1).map((p, i) => ({ id: String(p.id), source: 'local', name: p.name, coverUrl: p.cover_url, trackCount: p.track_count, lastOpenedAt: now - i * 3600e3, openCount: 20 - i })),
  get_home_local_playlists: localPlaylists,
  list_playlists: localPlaylists.map(p => ({ ...p, id: Number(p.id) })),
  get_playlist_tracks: ({ id }: { id: string | number }) => (LOCAL_ORDER[String(id)] || LOCAL_ORDER[1]).map((k, i) => localTrack(byKey(k), i)),
  get_netease_playlist_detail: neteasePlaylist,
  list_favorite_playlists: [...favoritePlaylists, ...artistFavorites],
  get_high_quality_playlists: highQuality,
  get_system_accent_color: 'rgb(0, 120, 212)',
  get_netease_artist_detail: ({ artistId }: { artistId: string }) => {
    const a = artistOf(artistId)
    return { code: 200, data: { artist: {
      name: a.name, alias: [], cover: NET_COVER(a.cover), avatar: NET_COVER(a.cover), musicSize: a.songs.length, albumSize: 1,
      briefDesc: EN ? `${a.name} — sample artist from the NeriPlayer PV.` : `${a.name}，NeriPlayer PV 里的示例歌手。`,
    } } }
  },
  get_netease_artist_songs: ({ artistId }: { artistId: string }) => ({ code: 200, songs: artistOf(artistId).songs.map(netSong) }),
  get_netease_artist_albums: ({ artistId }: { artistId: string }) => {
    const a = artistOf(artistId)
    return { code: 200, hotAlbums: [...new Set(a.songs.map(k => byKey(k)[3]))].map((name, i) => ({
      id: Number(a.id) * 10 + i, name, picUrl: NET_COVER(a.songs[i] || a.cover), publishTime: Date.UTC(2026, 3 + i, 1), size: a.songs.length,
    })) }
  },
  get_bili_artist_detail: ({ mid }: { mid: number }) => {
    const a = artistOf(mid)
    return {
      header: { name: a.name, coverUrl: COVER(a.cover), bannerUrl: COVER(a.cover), description: a.subtitle },
      tracks: a.songs.map((k, i) => ({ ...localTrack(byKey(k), i), id: `bilibili:BV1neri${i}`, source: 'bilibili' })),
      page: 1, total: a.songs.length, hasMore: false,
    }
  },
  get_bili_artist_contents: { collections: [], series: [], page: 1, hasMore: false },
  get_playback_stats_overview: statsOverview,
  get_playback_stat_tracks: ({ identityKeys }: { identityKeys: string[] }) => identityKeys.map(k => {
    const s = SONGS.find(x => `local:${x[0]}` === k)
    return s ? localTrack(s, 0) : null
  }),
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
