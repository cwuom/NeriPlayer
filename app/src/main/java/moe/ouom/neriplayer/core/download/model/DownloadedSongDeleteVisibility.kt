package moe.ouom.neriplayer.core.download

import java.util.concurrent.atomic.AtomicLong

/**
 * keeps a deletion invisible until its storage result has been settled
 */
internal class DownloadedSongDeleteVisibility {
    private val stateLock = Any()
    private val nextTokenId = AtomicLong(0L)
    private val activeTokenIdsByIdentity = mutableMapOf<String, Long>()
    private val baselineSongsByIdentity = mutableMapOf<String, DownloadedSong>()
    private val physicallyDeletedIdentities = mutableSetOf<String>()

    class Token internal constructor(
        internal val id: Long,
        internal val identities: Set<String>,
        internal val baselineSongsByIdentity: Map<String, DownloadedSong>
    )

    fun begin(songs: Collection<DownloadedSong>): Token {
        val identities = songs
            .mapTo(linkedSetOf()) { song -> song.deletionIdentity().trim() }
            .filter(String::isNotBlank)
            .toSet()
        val tokenId = nextTokenId.incrementAndGet()
        synchronized(stateLock) {
            val baselines = identities.associateWith { identity ->
                baselineSongsByIdentity[identity] ?: songs.first { song ->
                    song.deletionIdentity().trim() == identity
                }.also { song ->
                    baselineSongsByIdentity[identity] = song
                }
            }
            identities.forEach { identity ->
                activeTokenIdsByIdentity[identity] = tokenId
            }
            return Token(
                id = tokenId,
                identities = identities,
                baselineSongsByIdentity = baselines
            )
        }
    }

    fun recordDeleted(token: Token, songs: Collection<DownloadedSong>) {
        synchronized(stateLock) {
            songs.forEach { song ->
                val identity = song.deletionIdentity().trim()
                if (identity in token.identities) {
                    physicallyDeletedIdentities += identity
                }
            }
        }
    }

    fun wasPhysicallyDeleted(token: Token, song: DownloadedSong): Boolean {
        val identity = song.deletionIdentity().trim()
        return synchronized(stateLock) {
            identity in token.identities && identity in physicallyDeletedIdentities
        }
    }

    fun filterVisible(songs: List<DownloadedSong>): List<DownloadedSong> {
        if (songs.isEmpty()) {
            return songs
        }
        return synchronized(stateLock) {
            songs.filterNot { song ->
                song.deletionIdentity().trim() in activeTokenIdsByIdentity
            }
        }
    }

    fun owns(token: Token, song: DownloadedSong): Boolean {
        val identity = song.deletionIdentity().trim()
        return synchronized(stateLock) {
            identity in token.identities && activeTokenIdsByIdentity[identity] == token.id
        }
    }

    fun finish(token: Token) {
        synchronized(stateLock) {
            token.identities.forEach { identity ->
                if (activeTokenIdsByIdentity[identity] == token.id) {
                    activeTokenIdsByIdentity.remove(identity)
                    baselineSongsByIdentity.remove(identity)
                    physicallyDeletedIdentities.remove(identity)
                }
            }
        }
    }

    fun hasActiveDeletions(): Boolean = synchronized(stateLock) {
        activeTokenIdsByIdentity.isNotEmpty()
    }
}
