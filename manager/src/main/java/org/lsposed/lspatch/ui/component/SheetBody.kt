package org.lsposed.lspatch.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.matrix.vector.ui.LocalDialogLocalizer

/**
 * The body of a modal bottom sheet.
 *
 * Three things every sheet needs and none of them got by default, kept in one place so the next sheet does not
 * have to remember them:
 *
 * - **The reader's language.** A sheet is its own window, and a new window takes a fresh set of Android
 *   composition locals from that window's context, dropping the in-app language override on the way in.
 * - **Room for the keyboard.** A sheet is laid out against the window, not against the inset, so a text field in
 *   one sits under the keyboard the moment it is focused -- along with whatever the field was going to say about
 *   what was typed.
 * - **Somewhere to scroll.** A sheet tall enough to need it, or shortened by the keyboard to need it, otherwise
 *   just loses its lower half with no way to reach it.
 *
 * Not for a sheet whose content scrolls itself: nesting a scrollable in another of the same direction is an
 * error, so such a sheet applies [androidx.compose.foundation.layout.imePadding] on its own.
 */
@Composable
fun SheetBody(content: @Composable ColumnScope.() -> Unit) {
    LocalDialogLocalizer.current {
        Column(
            Modifier.imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding(),
            content = content,
        )
    }
}
