package moe.ouom.neriplayer.util

import android.content.Context
import moe.ouom.neriplayer.util.media.saveCoverToPictures as saveCoverToPicturesImpl

suspend fun saveCoverToPictures(
    context: Context,
    imageUrl: String,
    suggestedName: String
): Result<String> {
    return saveCoverToPicturesImpl(
        context = context,
        imageUrl = imageUrl,
        suggestedName = suggestedName
    )
}
