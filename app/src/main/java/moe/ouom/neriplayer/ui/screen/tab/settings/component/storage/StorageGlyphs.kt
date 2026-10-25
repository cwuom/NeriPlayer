package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object StorageGlyphs {
    val Audio: ImageVector by lazy {
        storageGlyph("StorageAudio") {
            outline {
                circle(12f, 12f, 8.5f)
                moveTo(8f, 10f); lineTo(8f, 14f)
                moveTo(12f, 7.5f); lineTo(12f, 16.5f)
                moveTo(16f, 10f); lineTo(16f, 14f)
            }
        }
    }

    val Image: ImageVector by lazy {
        storageGlyph("StorageImage") {
            outline {
                moveTo(16f, 3f); lineTo(5.5f, 3f)
                curveTo(4.1f, 3f, 3f, 4.1f, 3f, 5.5f); lineTo(3f, 16f)
                artwork(6f, 6f, 21f, 21f)
            }
        }
    }

    val DownloadStaging: ImageVector by lazy {
        storageGlyph("StorageDownloadStaging") {
            outline {
                moveTo(12f, 3f); lineTo(12f, 14f)
                moveTo(8f, 10f); lineTo(12f, 14f); lineTo(16f, 10f)
                moveTo(4f, 13f); lineTo(4f, 18f)
                curveTo(4f, 19.7f, 5.3f, 21f, 7f, 21f); lineTo(17f, 21f)
                curveTo(18.7f, 21f, 20f, 19.7f, 20f, 18f); lineTo(20f, 13f)
            }
        }
    }

    val Share: ImageVector by lazy {
        storageGlyph("StorageShare") {
            outline {
                circle(5.5f, 12f, 2.5f)
                circle(18f, 5f, 2.5f)
                circle(18f, 19f, 2.5f)
                moveTo(7.7f, 10.8f); lineTo(15.8f, 6.2f)
                moveTo(7.7f, 13.2f); lineTo(15.8f, 17.8f)
            }
        }
    }

    val Lyrics: ImageVector by lazy {
        storageGlyph("StorageLyrics") {
            outline {
                roundedRect(3.5f, 3.5f, 20.5f, 20.5f, 3f)
                moveTo(7f, 8f); lineTo(12f, 8f)
                moveTo(7f, 11.5f); lineTo(10.5f, 11.5f)
                note(14f, 16f, 16.5f, 9f)
            }
        }
    }

    val Playlist: ImageVector by lazy {
        storageGlyph("StoragePlaylist") {
            outline {
                moveTo(4f, 5f); lineTo(15f, 5f)
                moveTo(4f, 10f); lineTo(11f, 10f)
                moveTo(4f, 15f); lineTo(8f, 15f)
                note(15f, 18f, 18f, 9f)
            }
        }
    }

    val Favorite: ImageVector by lazy {
        storageGlyph("StorageFavorite") {
            outline {
                folder()
                moveTo(12f, 17.5f); lineTo(8.8f, 14.5f)
                curveTo(6.4f, 12.2f, 9.5f, 9.5f, 12f, 12f)
                curveTo(14.5f, 9.5f, 17.6f, 12.2f, 15.2f, 14.5f)
                lineTo(12f, 17.5f); close()
            }
        }
    }

    val Archive: ImageVector by lazy {
        storageGlyph("StorageArchive") {
            outline {
                roundedRect(3f, 4f, 21f, 8f, 1.5f)
                moveTo(5f, 8f); lineTo(5f, 18.5f)
                curveTo(5f, 19.9f, 6.1f, 21f, 7.5f, 21f); lineTo(16.5f, 21f)
                curveTo(17.9f, 21f, 19f, 19.9f, 19f, 18.5f); lineTo(19f, 8f)
                moveTo(10f, 11.5f); lineTo(15f, 14.5f); lineTo(10f, 17.5f); close()
            }
        }
    }

    val VideoPlaylist: ImageVector by lazy {
        storageGlyph("StorageVideoPlaylist") {
            outline {
                moveTo(7f, 3f); lineTo(17f, 3f)
                moveTo(5f, 6f); lineTo(19f, 6f)
                roundedRect(3f, 9f, 21f, 21f, 2.5f)
                moveTo(10f, 12f); lineTo(15f, 15f); lineTo(10f, 18f); close()
            }
        }
    }

    val Layers: ImageVector by lazy {
        storageGlyph("StorageLayers") {
            outline {
                moveTo(3f, 7.5f); lineTo(12f, 3f); lineTo(21f, 7.5f)
                lineTo(12f, 12f); close()
                moveTo(3f, 12f); lineTo(12f, 16.5f); lineTo(21f, 12f)
                moveTo(3f, 16.5f); lineTo(12f, 21f); lineTo(21f, 16.5f)
            }
        }
    }

    val Protected: ImageVector by lazy {
        storageGlyph("StorageProtected") {
            outline {
                moveTo(12f, 2.5f); lineTo(20f, 6f); lineTo(20f, 11f)
                curveTo(20f, 16f, 16.5f, 19.5f, 12f, 21.5f)
                curveTo(7.5f, 19.5f, 4f, 16f, 4f, 11f)
                lineTo(4f, 6f); close()
                roundedRect(8.5f, 10.5f, 15.5f, 16f, 1.5f)
                moveTo(10f, 10.5f); lineTo(10f, 8.5f)
                curveTo(10f, 5.8f, 14f, 5.8f, 14f, 8.5f); lineTo(14f, 10.5f)
            }
        }
    }

    val DownloadedMusic: ImageVector by lazy {
        storageGlyph("StorageDownloadedMusic") {
            outline {
                oval(3.5f, 14.25f, 9f, 17.75f)
                moveTo(9f, 16f); lineTo(9f, 5f); lineTo(18f, 3f); lineTo(18f, 10f)
                oval(12.5f, 8.25f, 18f, 11.75f)
                moveTo(14f, 18f); lineTo(17f, 21f); lineTo(22f, 15f)
            }
        }
    }

    val DownloadedLyrics: ImageVector by lazy {
        storageGlyph("StorageDownloadedLyrics") {
            outline {
                document()
                moveTo(7f, 8f); lineTo(15f, 8f)
                moveTo(7f, 12f); lineTo(13f, 12f)
                moveTo(7f, 16f); lineTo(10f, 16f)
                completed()
            }
        }
    }

    val DownloadedCovers: ImageVector by lazy {
        storageGlyph("StorageDownloadedCovers") {
            outline {
                moveTo(20f, 11f); lineTo(20f, 6.5f)
                curveTo(20f, 4.6f, 18.4f, 3f, 16.5f, 3f); lineTo(6.5f, 3f)
                curveTo(4.6f, 3f, 3f, 4.6f, 3f, 6.5f); lineTo(3f, 16.5f)
                curveTo(3f, 18.4f, 4.6f, 20f, 6.5f, 20f); lineTo(11f, 20f)
                circle(8f, 8f, 1.5f)
                moveTo(3f, 16f); lineTo(9f, 11f); lineTo(13f, 14.5f)
                lineTo(16f, 12f); lineTo(18f, 13.5f)
                completed()
            }
        }
    }

    val Index: ImageVector by lazy {
        storageGlyph("StorageIndex") {
            outline {
                roundedRect(4f, 3f, 20f, 21f, 2.5f)
                moveTo(4f, 8f); lineTo(20f, 8f)
                moveTo(8f, 12f); lineTo(8f, 12.01f)
                moveTo(12f, 12f); lineTo(16f, 12f)
                moveTo(8f, 16.5f); lineTo(8f, 16.51f)
                moveTo(12f, 16.5f); lineTo(16f, 16.5f)
            }
        }
    }

    val Logs: ImageVector by lazy {
        storageGlyph("StorageLogs") {
            outline {
                roundedRect(4f, 3f, 20f, 21f, 2.5f)
                moveTo(8f, 7.5f); lineTo(16f, 7.5f)
                moveTo(8f, 12f); lineTo(16f, 12f)
                moveTo(8f, 16.5f); lineTo(12.5f, 16.5f)
            }
        }
    }

    val Crash: ImageVector by lazy {
        storageGlyph("StorageCrash") {
            outline {
                document()
                moveTo(7f, 7.5f); lineTo(14f, 7.5f)
                moveTo(7f, 11.5f); lineTo(10f, 11.5f)
                moveTo(17.5f, 12f); lineTo(22f, 20.5f); lineTo(13f, 20.5f); close()
                moveTo(17.5f, 15.5f); lineTo(17.5f, 17f)
                moveTo(17.5f, 18.8f); lineTo(17.5f, 18.81f)
            }
        }
    }

    val LocalCovers: ImageVector by lazy {
        storageGlyph("StorageLocalCovers") {
            outline {
                folder()
                circle(9f, 10.5f, 1f)
                moveTo(6.5f, 17f); lineTo(11f, 12.5f); lineTo(14f, 15f)
                lineTo(16f, 13f); lineTo(19f, 16f)
            }
        }
    }

    val Background: ImageVector by lazy {
        storageGlyph("StorageBackground") {
            outline {
                roundedRect(3f, 3f, 21f, 21f, 3.5f)
                circle(16f, 8f, 2f)
                moveTo(3f, 15f)
                curveTo(7f, 10f, 10f, 10f, 14f, 15f)
                curveTo(16f, 17.5f, 18f, 18f, 21f, 15f)
                moveTo(3.5f, 18.5f)
                curveTo(7f, 15f, 10f, 15f, 13f, 18f)
            }
        }
    }

    val Migration: ImageVector by lazy {
        storageGlyph("StorageMigration") {
            outline {
                roundedRect(3f, 3f, 12f, 14f, 2f)
                roundedRect(12f, 10f, 21f, 21f, 2f)
                moveTo(15.5f, 4f); lineTo(20f, 4f); lineTo(20f, 7.5f)
                moveTo(17.5f, 6f); lineTo(20f, 8.5f); lineTo(22f, 6f)
                moveTo(8.5f, 20f); lineTo(4f, 20f); lineTo(4f, 16.5f)
            }
        }
    }

    val Database: ImageVector by lazy {
        storageGlyph("StorageDatabase") {
            outline {
                oval(4f, 3f, 20f, 10f)
                moveTo(4f, 6.5f); lineTo(4f, 17.5f)
                curveTo(4f, 22.2f, 20f, 22.2f, 20f, 17.5f); lineTo(20f, 6.5f)
                moveTo(4f, 12f); curveTo(4f, 16.7f, 20f, 16.7f, 20f, 12f)
            }
        }
    }

    val AppData: ImageVector by lazy {
        storageGlyph("StorageAppData") {
            outline {
                roundedRect(3f, 3f, 11f, 11f, 2f)
                roundedRect(14f, 3f, 21f, 10f, 2f)
                roundedRect(3f, 14f, 10f, 21f, 2f)
                moveTo(14f, 15f); lineTo(21f, 15f)
                moveTo(14f, 20f); lineTo(18f, 20f)
            }
        }
    }

    val Storage: ImageVector by lazy {
        storageGlyph("StorageOverview") {
            outline {
                roundedRect(3.5f, 3.5f, 20.5f, 10.5f, 2.5f)
                roundedRect(3.5f, 13.5f, 20.5f, 20.5f, 2.5f)
                moveTo(7.5f, 7f); lineTo(7.5f, 7.01f)
                moveTo(7.5f, 17f); lineTo(7.5f, 17.01f)
                moveTo(12f, 7f); lineTo(16.5f, 7f)
                moveTo(12f, 17f); lineTo(16.5f, 17f)
            }
        }
    }

    val Scan: ImageVector by lazy {
        storageGlyph("StorageScan") {
            outline {
                moveTo(3f, 8f); lineTo(3f, 5f)
                curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f); lineTo(8f, 3f)
                moveTo(16f, 3f); lineTo(19f, 3f)
                curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f); lineTo(21f, 8f)
                moveTo(21f, 16f); lineTo(21f, 19f)
                curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f); lineTo(16f, 21f)
                moveTo(8f, 21f); lineTo(5f, 21f)
                curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f); lineTo(3f, 16f)
                moveTo(7f, 9f); lineTo(17f, 9f)
                moveTo(7f, 12f); lineTo(17f, 12f)
                moveTo(7f, 15f); lineTo(13f, 15f)
            }
        }
    }

    val Refresh: ImageVector by lazy {
        storageGlyph("StorageRefresh") {
            outline {
                moveTo(20f, 9f)
                curveTo(18.7f, 5f, 14.6f, 2.6f, 10.4f, 3.5f)
                curveTo(6.2f, 4.4f, 3.3f, 8.3f, 3.7f, 12.6f)
                curveTo(4.1f, 16.9f, 7.7f, 20.3f, 12f, 20.3f)
                curveTo(15.4f, 20.3f, 18.4f, 18.3f, 19.7f, 15.3f)
                moveTo(20f, 3.5f); lineTo(20f, 9f); lineTo(14.5f, 9f)
            }
        }
    }

    val Clear: ImageVector by lazy {
        storageGlyph("StorageClear") {
            outline {
                moveTo(3.5f, 6.5f); lineTo(20.5f, 6.5f)
                moveTo(9f, 6.5f); lineTo(9f, 4.5f)
                curveTo(9f, 3.7f, 9.7f, 3f, 10.5f, 3f); lineTo(13.5f, 3f)
                curveTo(14.3f, 3f, 15f, 3.7f, 15f, 4.5f); lineTo(15f, 6.5f)
                moveTo(5.5f, 6.5f); lineTo(6.5f, 18.5f)
                curveTo(6.6f, 19.9f, 7.7f, 21f, 9f, 21f); lineTo(15f, 21f)
                curveTo(16.3f, 21f, 17.4f, 19.9f, 17.5f, 18.5f); lineTo(18.5f, 6.5f)
                moveTo(10f, 10.5f); lineTo(10.5f, 17f)
                moveTo(14f, 10.5f); lineTo(13.5f, 17f)
            }
        }
    }
}

private fun storageGlyph(name: String, draw: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(draw).build()

private fun ImageVector.Builder.outline(draw: PathBuilder.() -> Unit) {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = draw
    )
}

private fun PathBuilder.roundedRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
    moveTo(left + radius, top); lineTo(right - radius, top)
    quadTo(right, top, right, top + radius); lineTo(right, bottom - radius)
    quadTo(right, bottom, right - radius, bottom); lineTo(left + radius, bottom)
    quadTo(left, bottom, left, bottom - radius); lineTo(left, top + radius)
    quadTo(left, top, left + radius, top); close()
}

private fun PathBuilder.circle(centerX: Float, centerY: Float, radius: Float) {
    oval(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
}

private fun PathBuilder.oval(left: Float, top: Float, right: Float, bottom: Float) {
    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f
    val controlX = (right - left) / 2f * CIRCLE_CONTROL_RATIO
    val controlY = (bottom - top) / 2f * CIRCLE_CONTROL_RATIO
    moveTo(centerX, top)
    curveTo(centerX + controlX, top, right, centerY - controlY, right, centerY)
    curveTo(right, centerY + controlY, centerX + controlX, bottom, centerX, bottom)
    curveTo(centerX - controlX, bottom, left, centerY + controlY, left, centerY)
    curveTo(left, centerY - controlY, centerX - controlX, top, centerX, top); close()
}

private fun PathBuilder.artwork(left: Float, top: Float, right: Float, bottom: Float) {
    roundedRect(left, top, right, bottom, 2.5f)
    circle(left + 4f, top + 4f, 1.2f)
    moveTo(left, bottom - 4f); lineTo(left + 5f, bottom - 9f)
    lineTo(left + 8f, bottom - 6f); lineTo(right - 3f, bottom - 10f); lineTo(right, bottom - 7f)
}

private fun PathBuilder.note(centerX: Float, centerY: Float, stemX: Float, stemTop: Float) {
    oval(centerX - 2.5f, centerY - 1.75f, stemX, centerY + 1.75f)
    moveTo(stemX, centerY); lineTo(stemX, stemTop); lineTo(stemX + 3f, stemTop - 0.75f)
}

private fun PathBuilder.folder() {
    moveTo(3f, 8f); lineTo(3f, 6f)
    curveTo(3f, 4.9f, 3.9f, 4f, 5f, 4f); lineTo(9f, 4f)
    lineTo(11f, 6.5f); lineTo(19f, 6.5f)
    curveTo(20.1f, 6.5f, 21f, 7.4f, 21f, 8.5f); lineTo(21f, 18f)
    curveTo(21f, 19.1f, 20.1f, 20f, 19f, 20f); lineTo(5f, 20f)
    curveTo(3.9f, 20f, 3f, 19.1f, 3f, 18f); lineTo(3f, 8f)
}

private fun PathBuilder.document() {
    moveTo(11f, 21f); lineTo(6.5f, 21f)
    curveTo(5.1f, 21f, 4f, 19.9f, 4f, 18.5f); lineTo(4f, 5.5f)
    curveTo(4f, 4.1f, 5.1f, 3f, 6.5f, 3f); lineTo(16.5f, 3f)
    curveTo(17.9f, 3f, 19f, 4.1f, 19f, 5.5f); lineTo(19f, 10f)
}

private fun PathBuilder.completed() {
    moveTo(13.5f, 17.5f); lineTo(16.5f, 20.5f); lineTo(22f, 14.5f)
}

private const val CIRCLE_CONTROL_RATIO = 0.5522848f
