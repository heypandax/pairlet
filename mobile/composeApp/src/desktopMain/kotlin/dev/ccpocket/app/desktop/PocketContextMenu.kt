package dev.ccpocket.app.desktop

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.rememberPopupPositionProviderAtPosition
import dev.ccpocket.app.theme.Tok

/**
 * A [ContextMenuItem] carrying the presentation facts the design's menu draws from ("Context Menu v1"
 * handoff): [removal] = the destructive-ish tail verb, normal at rest and danger-tinted only under the
 * pointer (a red label at rest pulls the eye down the whole menu); [mutedPrefix] = the two-tone
 * "Move to group ·" lead-in. Plain [ContextMenuItem]s (text-field cut/copy/paste) render as NORMAL rows,
 * which is what keeps this one representation serving every menu in the app.
 */
class PocketMenuItem(
    label: String,
    val removal: Boolean = false,
    val mutedPrefix: String? = null,
    onClick: () -> Unit,
) : ContextMenuItem(label, onClick)

/** Marker rendered as the family separator rule — never a row. See [joinMenuFamilies]. */
object PocketMenuSeparator : ContextMenuItem("", {})

/** Join verb families into one flat item list with a [PocketMenuSeparator] at every non-empty boundary —
 *  the caller thinks in families (navigate / edit / file / remove), the wire stays a flat list. */
fun joinMenuFamilies(vararg families: List<ContextMenuItem>): List<ContextMenuItem> =
    families.filter { it.isNotEmpty() }
        .reduceOrNull { acc, f -> acc + PocketMenuSeparator + f } ?: emptyList()

/**
 * The app-wide context menu (design "Context Menu v1"): raised surface, hairline border, r10, and the
 * macOS-style inner-pill hover. Installed once over the whole shell via [LocalContextMenuRepresentation]
 * in [dev.ccpocket.app.main], so the sidebar's session menus and every text field's cut/copy/paste all
 * stop rendering the stock Swing-grey menu.
 */
object PocketContextMenuRepresentation : ContextMenuRepresentation {
    private val openMenus = androidx.compose.runtime.mutableStateOf(0)

    /** Any context menu on screen — the sidebar's hover-reveal holds itself open on this, so a panel
     *  can't slide away under the menu it just spawned (the popup is a sibling window; the pointer
     *  moving into it reads as "left the sidebar"). */
    val anyMenuOpen: Boolean get() = openMenus.value > 0

    // rememberPopupPositionProviderAtPosition — the same cursor-anchored provider the stock
    // representation uses; experimental in name only for this positioning use.
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val status = state.status as? ContextMenuState.Status.Open ?: return
        androidx.compose.runtime.DisposableEffect(Unit) {
            openMenus.value++
            onDispose { openMenus.value-- }
        }
        val close = { state.status = ContextMenuState.Status.Closed }
        Popup(
            popupPositionProvider = rememberPopupPositionProviderAtPosition(positionPx = status.rect.center),
            onDismissRequest = close,
            properties = PopupProperties(focusable = true), // the popup owns the keyboard — Esc must close from inside
        ) {
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier
                    .shadow(12.dp, shape)
                    .clip(shape)
                    .background(Tok.raised)
                    .border(1.dp, Tok.hair, shape)
                    .padding(vertical = 5.dp)
                    .widthIn(min = 180.dp, max = 260.dp)
                    .width(IntrinsicSize.Max)
                    .verticalScroll(rememberScrollState()) // a menu taller than the window still reaches its tail
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { close(); true } else false
                    },
            ) {
                items().forEach { item ->
                    if (item === PocketMenuSeparator) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(1.dp).background(Tok.hair))
                    } else {
                        MenuRow(item) { close(); item.onClick() }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(item: ContextMenuItem, onPick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val removal = (item as? PocketMenuItem)?.removal == true
    val lit = hovered || pressed
    val fill = when {
        removal && lit -> Tok.danger.copy(alpha = 0.12f)
        pressed -> PRESSED_FILL
        hovered -> Tok.hair
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(28.dp)
            .clip(RoundedCornerShape(6.dp)).background(fill)
            .hoverable(src).clickable(interactionSource = src, indication = null, onClick = onPick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        (item as? PocketMenuItem)?.mutedPrefix?.let {
            Text(it, color = Tok.tx2, fontFamily = Dk.ui, fontSize = 12.5.sp, style = tightCenter(12.5.sp), maxLines = 1)
        }
        Text(
            item.label, color = if (removal && lit) Tok.danger else Tok.tx,
            fontFamily = Dk.ui, fontSize = 12.5.sp, style = tightCenter(12.5.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The mock's pressed step (#33383E) — one notch above the hover fill. */
private val PRESSED_FILL = Color(0xFF33383E)
