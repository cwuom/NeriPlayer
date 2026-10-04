package moe.ouom.neriplayer.data.model.settings.lyrics

enum class BluetoothMetadataMode(val storageValue: String) {
    Lyrics("lyrics"),
    SongInfo("song_info"),
    SongAndLyrics("song_and_lyrics");

    companion object {
        fun fromStorage(value: String?): BluetoothMetadataMode =
            entries.firstOrNull { it.storageValue == value } ?: SongAndLyrics
    }
}
