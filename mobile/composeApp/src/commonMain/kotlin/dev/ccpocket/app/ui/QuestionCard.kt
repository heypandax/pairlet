package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.PermissionAsk
import org.jetbrains.compose.resources.stringResource

/** Marks the synthetic "Other…" row in a question's selection set (never a real option label — claude's
 *  schema forbids the model from offering an "Other" option; the host supplies it). */
private const val OTHER = " other"

// accent tints from the design board (qm-core.jsx): A08/A12/A18/A28/A55
private val A08 get() = Tok.accent.copy(alpha = 0.08f)
private val A12 get() = Tok.accent.copy(alpha = 0.12f)
private val A18 get() = Tok.accent.copy(alpha = 0.18f)
private val A28 get() = Tok.accent.copy(alpha = 0.28f)
private val A55 get() = Tok.accent.copy(alpha = 0.55f)

/**
 * The AskUserQuestion card — Claude asking the user 1–4 multiple-choice questions. Docked above the
 * composer (the chat stays scrollable), deliberately calmer than the PermissionSheet: accent-colored,
 * no countdown, no warning colors. Answers go back as question-text → label(s)/free text; the
 * "Reply in your own words…" mode sends a freeform [dev.ccpocket.protocol.PermissionVerdict.response]
 * instead. Visuals follow the claude-design board (docs/design/claude-design-handoff/ask-question).
 */
@Composable
fun QuestionCard(
    ask: PermissionAsk,
    onAnswer: (answers: Map<String, String>?, response: String?) -> Unit,
    onSkip: () -> Unit,
    onOwnsInput: (Boolean) -> Unit = {},
) {
    val questions = ask.questions.orEmpty()
    if (questions.isEmpty()) return
    var qIndex by remember(ask.askId) { mutableStateOf(0) }
    val selections = remember(ask.askId) { mutableStateMapOf<Int, Set<String>>() }
    val otherTexts = remember(ask.askId) { mutableStateMapOf<Int, String>() }
    var freeform by remember(ask.askId) { mutableStateOf(false) }
    var freeformText by remember(ask.askId) { mutableStateOf("") }

    // a question's outgoing answer: selected labels in option order, "Other…" text last, comma-joined
    fun answerOf(i: Int): String? {
        val sel = selections[i].orEmpty()
        val labels = questions[i].options.map { it.label }.filter { it in sel }
        val other = if (OTHER in sel) otherTexts[i]?.trim().takeIf { !it.isNullOrBlank() } else null
        return (labels + listOfNotNull(other)).takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    val allAnswered = questions.indices.all { answerOf(it) != null }
    val multiQ = questions.size > 1
    val shape = RoundedCornerShape(16.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // cap the docked card's height so a tall question (many options / 2+ questions) scrolls INSIDE the
        // card instead of shoving the skip/submit row off the bottom of the screen, unreachable (#125 —
        // the card docks above the composer with natural height; without a cap Android can't reach its foot).
        // Only under a BOUNDED host though: in an unbounded-height host (desktop docks the card in a
        // LazyColumn tail item) maxHeight is Infinity — the cap can't bind and the weighted middle measures
        // at minHeight=0, collapsing the card to an empty header+footer shell (#150). Detect that and fall
        // back to natural height with no inner scroll (the host's own scrolling takes over).
        val bounded = constraints.hasBoundedHeight
        Box(
        Modifier.fillMaxWidth()
            .then(if (bounded) Modifier.heightIn(max = maxHeight * 0.62f) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .shadow(14.dp, shape).clip(shape).background(Tok.raised).border(1.dp, Tok.hair, shape),
    ) {
        // the quiet arrival cue: a centered terracotta top-hairline that fades at both ends
        Box(
            Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp).fillMaxWidth().height(2.dp)
                .background(Brush.horizontalGradient(listOf(Tok.accent.copy(alpha = 0f), Tok.accent.copy(alpha = 0.75f), Tok.accent.copy(alpha = 0f)))),
        )
        Column(Modifier.padding(start = 15.dp, end = 15.dp, top = 14.dp, bottom = 15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                QBadge(28.dp)
                Text(stringResource(Res.string.question_header), color = Tok.tx2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (multiQ) {
                    Text(
                        stringResource(Res.string.question_progress, qIndex + 1, questions.size),
                        color = Tok.muted, fontSize = 11.5.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, letterSpacing = 0.3.sp,
                    )
                }
            }
            if (multiQ && !freeform) {
                Row(
                    Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    questions.forEachIndexed { i, q ->
                        ChipTab(
                            label = q.header?.takeIf { it.isNotBlank() } ?: (i + 1).toString(),
                            active = i == qIndex, done = answerOf(i) != null,
                        ) { qIndex = i }
                    }
                }
            }
            // scrollable middle — header + chip-tabs (above) and skip/submit (below) stay pinned, so a tall
            // option list / freeform field scrolls HERE instead of overflowing the capped card (#125).
            // weight() only under a bounded host — with infinite incoming height it measures at 0 (#150).
            Column(if (bounded) Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()) else Modifier) {
            if (freeform) {
                var focused by remember { mutableStateOf(false) }
                val ffShape = RoundedCornerShape(12.dp)
                Box(
                    Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(min = 92.dp).clip(ffShape)
                        .background(Tok.base).border(1.dp, if (focused) Tok.accent else Tok.hair, ffShape)
                        .padding(horizontal = 13.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        freeformText, { freeformText = it },
                        textStyle = TextStyle(color = Tok.tx, fontSize = 14.sp, lineHeight = 21.sp),
                        cursorBrush = SolidColor(Tok.accent),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; onOwnsInput(it.isFocused) },
                    )
                    if (freeformText.isEmpty()) Text(stringResource(Res.string.question_freeform_hint), color = Tok.muted, fontSize = 14.sp)
                }
                ReplyLink(back = true) { freeform = false; onOwnsInput(false) }
            } else {
                val q = questions[qIndex]
                Text(
                    q.question, color = Tok.tx, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 21.sp, modifier = Modifier.padding(top = if (multiQ) 12.dp else 13.dp),
                )
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    q.options.forEach { opt ->
                        OptionRow(
                            label = opt.label, description = opt.description, multi = q.multiSelect,
                            selected = opt.label in selections[qIndex].orEmpty(),
                        ) {
                            val next = toggle(selections[qIndex].orEmpty(), opt.label, q.multiSelect)
                            selections[qIndex] = next
                            // auto-advance like the Claude Code terminal: picking a single-select answer
                            // steps to the next question so a multi-question card flows without a manual tab
                            // tap. Multi-select stays put (the user keeps ticking boxes); a deselect (empty
                            // set) doesn't advance; the last question holds so the submit button stays in view.
                            // The chip-tabs above still navigate back to revise an answered question. (#71)
                            if (!q.multiSelect && opt.label in next && qIndex < questions.lastIndex) qIndex++
                        }
                    }
                    // the host-supplied "Other…" row (claude never offers one) — expands into a text field
                    val otherOn = OTHER in selections[qIndex].orEmpty()
                    OtherRow(
                        multi = q.multiSelect, selected = otherOn,
                        value = otherTexts[qIndex].orEmpty(),
                        onValueChange = { otherTexts[qIndex] = it },
                        onToggle = { selections[qIndex] = toggle(selections[qIndex].orEmpty(), OTHER, q.multiSelect) },
                        onFocus = onOwnsInput,
                    )
                }
                ReplyLink(back = false) { freeform = true }
            }
            } // end scrollable middle (#125)
            Row(Modifier.padding(top = 16.dp).fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.question_skip), color = Tok.muted, fontSize = 14.5.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSkip).padding(horizontal = 8.dp, vertical = 11.dp),
                )
                Spacer(Modifier.weight(1f))
                val ready = if (freeform) freeformText.isNotBlank() else allAnswered
                Box(
                    Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (ready) Tok.accent else Tok.surface)
                        .let { if (ready) it else it.border(1.dp, Tok.hair, RoundedCornerShape(12.dp)) }
                        .clickable(enabled = ready) {
                            if (freeform) onAnswer(null, freeformText.trim())
                            else onAnswer(questions.indices.mapNotNull { i -> answerOf(i)?.let { questions[i].question to it } }.toMap(), null)
                        }
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(Res.string.question_answer),
                        color = if (ready) Tok.base else Tok.muted, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 11.dp),
                    )
                }
            }
        }
    }
    }
}

/** Single/multi selection toggle: radios replace (Other included — one slot), checkboxes flip. */
private fun toggle(current: Set<String>, label: String, multi: Boolean): Set<String> = when {
    !multi -> if (label in current) emptySet() else setOf(label)
    label in current -> current - label
    else -> current + label
}

/** The terracotta question-mark badge (accent on 12% accent). */
@Composable
internal fun QBadge(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(A12), contentAlignment = Alignment.Center) {
        Text("?", color = Tok.accent, fontSize = (size.value * 0.56f).sp, fontWeight = FontWeight.Bold, style = TightCenter)
    }
}

/** Header chip-tab: active = filled accent tint, answered = faint tint + check, idle = hairline. */
@Composable
private fun ChipTab(label: String, active: Boolean, done: Boolean, onClick: () -> Unit) {
    val bg = if (active) A18 else if (done) A08 else androidx.compose.ui.graphics.Color.Transparent
    val bd = if (active) A55 else if (done) A28 else Tok.hair
    val col = if (active || done) Tok.accent else Tok.tx2
    Row(
        Modifier.height(27.dp).clip(RoundedCornerShape(999.dp)).background(bg)
            .border(1.dp, bd, RoundedCornerShape(999.dp)).clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (done) Icon(Icons.Rounded.Check, null, tint = Tok.accent, modifier = Modifier.size(11.dp))
        Text(label, color = col, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun OptionRow(label: String, description: String?, multi: Boolean, selected: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(shape)
            .background(if (selected) A08 else Tok.base)
            .border(1.dp, if (selected) Tok.accent else Tok.hair, shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.padding(top = 1.dp)) { SelectControl(multi, selected) }
        Column(Modifier.weight(1f)) {
            Text(label, color = Tok.tx, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp)
            if (!description.isNullOrBlank()) {
                Text(description, color = Tok.tx2, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** The "Other…" row: control + label collapsed; an inline field indented under the label when chosen. */
@Composable
private fun OtherRow(
    multi: Boolean,
    selected: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onToggle: () -> Unit,
    onFocus: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) A08 else Tok.base)
            .border(1.dp, if (selected) Tok.accent else Tok.hair, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            SelectControl(multi, selected)
            Text(
                stringResource(Res.string.question_other),
                color = if (selected) Tok.tx else Tok.tx2, fontSize = 14.5.sp, fontWeight = FontWeight.Medium,
            )
        }
        if (selected) {
            var focused by remember { mutableStateOf(false) }
            val fShape = RoundedCornerShape(9.dp)
            Box(
                Modifier.padding(start = 43.dp, end = 12.dp, bottom = 11.dp).fillMaxWidth()
                    .heightIn(min = 38.dp).clip(fShape).background(Tok.surface)
                    .border(1.dp, if (focused) Tok.accent else Tok.hair, fShape)
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value, onValueChange,
                    textStyle = TextStyle(color = Tok.tx, fontSize = 13.5.sp),
                    cursorBrush = SolidColor(Tok.accent),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) },
                )
                if (value.isEmpty()) Text(stringResource(Res.string.question_other_hint), color = Tok.muted, fontSize = 13.5.sp)
            }
        }
    }
}

/** 20dp radio (circle, 9dp dot) or checkbox (radius 6, filled + dark check) per the board. */
@Composable
private fun SelectControl(multi: Boolean, selected: Boolean) {
    val shape = if (multi) RoundedCornerShape(6.dp) else CircleShape
    Box(
        Modifier.size(20.dp).clip(shape)
            .background(if (selected && multi) Tok.accent else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.5.dp, if (selected) Tok.accent else Tok.hair, shape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            multi && selected -> Icon(Icons.Rounded.Check, null, tint = Tok.base, modifier = Modifier.size(13.dp))
            !multi && selected -> Box(Modifier.size(9.dp).clip(CircleShape).background(Tok.accent))
        }
    }
}

/** The quiet structured ⇄ freeform toggle: pencil / chevron-left + secondary label. */
@Composable
private fun ReplyLink(back: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.padding(top = 12.dp).heightIn(min = 24.dp).clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (back) Icons.Rounded.KeyboardArrowLeft else Icons.Rounded.Edit,
            null, tint = Tok.tx2, modifier = Modifier.size(13.dp),
        )
        Text(
            stringResource(if (back) Res.string.question_freeform_back else Res.string.question_freeform_link),
            color = Tok.tx2, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        )
    }
}

/** The compact answered row left in the transcript — tap to expand the question → answer list. */
@Composable
fun QuestionsAnsweredRow(items: List<Pair<String, String>>) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Tok.surface).border(1.dp, Tok.hair, shape)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            QBadge(22.dp)
            Text(
                stringResource(if (items.size == 1) Res.string.questions_answered_one else Res.string.questions_answered_many, items.size),
                color = Tok.tx2, fontSize = 13.sp,
            )
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                items.take(2).forEach { (_, a) -> TinyChip(a) }
                if (items.size > 2) TinyChip("+${items.size - 2}", muted = true)
            }
            Icon(
                Icons.Rounded.KeyboardArrowDown, null, tint = Tok.muted,
                modifier = Modifier.size(15.dp).rotate(if (open) 180f else 0f),
            )
        }
        if (open) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                items.forEachIndexed { i, (q, a) ->
                    Column(Modifier.padding(vertical = 7.dp)) {
                        if (q.isNotBlank()) Text(q, color = Tok.muted, fontSize = 11.5.sp, modifier = Modifier.padding(bottom = 3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(Icons.Rounded.Check, null, tint = Tok.accent, modifier = Modifier.size(12.dp))
                            Text(a, color = Tok.tx, fontSize = 13.5.sp)
                        }
                    }
                    if (i < items.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                }
            }
        }
    }
}

@Composable
private fun TinyChip(text: String, muted: Boolean = false) {
    Text(
        text, color = if (muted) Tok.muted else Tok.tx2, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 118.dp).clip(RoundedCornerShape(6.dp))
            .background(Tok.base).border(1.dp, Tok.hair, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Withdrawn: the muted one-liner left where the card used to be (dot + text, no drama). */
@Composable
fun QuestionsWithdrawnRow() {
    Row(
        Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Tok.muted))
        Text(stringResource(Res.string.questions_withdrawn), color = Tok.muted, fontSize = 13.sp)
    }
}
