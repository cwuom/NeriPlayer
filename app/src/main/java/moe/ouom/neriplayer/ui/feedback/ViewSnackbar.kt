package moe.ouom.neriplayer.ui.feedback

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.snackbar.Snackbar

private const val ViewSnackbarElevationDp = 12
private const val ViewSnackbarBottomMarginDp = 16
private const val ViewSnackbarMaxLines = 2

fun showNeriViewSnackbar(
    anchor: View,
    message: CharSequence,
    duration: Int
): Snackbar {
    val snackbar = Snackbar.make(anchor, message, duration)
    val density = anchor.resources.displayMetrics.density
    val view = snackbar.view
    view.elevation = ViewSnackbarElevationDp * density
    view.translationZ = ViewSnackbarElevationDp * density
    view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
        maxLines = ViewSnackbarMaxLines
    }
    val navigationBottom = ViewCompat.getRootWindowInsets(anchor)
        ?.getInsets(WindowInsetsCompat.Type.navigationBars())
        ?.bottom
        ?: 0
    val bottomMargin = navigationBottom + (ViewSnackbarBottomMarginDp * density).toInt()
    view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
        this.bottomMargin = this.bottomMargin.coerceAtLeast(bottomMargin)
    }
    snackbar.show()
    return snackbar
}
