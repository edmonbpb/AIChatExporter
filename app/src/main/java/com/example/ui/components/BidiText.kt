package com.example.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.example.data.model.TextDirection as AppTextDirection
import com.example.domain.bidi.BidiEngine

@Composable
fun BidiText(
    text: String,
    modifier: Modifier = Modifier,
    direction: AppTextDirection = AppTextDirection.MIXED,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    usePersianDigits: Boolean = false
) {
    val processedText = remember(text, usePersianDigits) {
        val normalized = BidiEngine.normalizePersianTypography(text)
        if (usePersianDigits) BidiEngine.toPersianDigits(normalized) else normalized
    }

    val annotated = remember(processedText) {
        parseMarkdownInline(processedText)
    }

    val textAlign = bidiTextAlign(direction, text)

    val composeDirection = bidiTextDirection(direction)

    Text(
        text = annotated,
        modifier = modifier,
        color = color,
        style = style.copy(
            textAlign = textAlign,
            textDirection = composeDirection
        )
    )
}

/**
 * Single source of the RTL alignment behaviour every piece of text in the app follows: a body
 * string routed through [BidiText] and a button label routed through [AdaptiveLabel] align the
 * same way, because both call these two functions.
 */
internal fun bidiTextAlign(direction: AppTextDirection, text: String): TextAlign = when (direction) {
    AppTextDirection.RTL -> TextAlign.Right
    AppTextDirection.LTR -> TextAlign.Left
    AppTextDirection.MIXED -> if (BidiEngine.detectDirection(text) == AppTextDirection.LTR) TextAlign.Left else TextAlign.Right
}

internal fun bidiTextDirection(direction: AppTextDirection): TextDirection = when (direction) {
    AppTextDirection.RTL -> TextDirection.Rtl
    AppTextDirection.LTR -> TextDirection.Ltr
    AppTextDirection.MIXED -> TextDirection.ContentOrRtl
}

/**
 * How a label should be rendered once the width it has to live in is known.
 * Produced by the pure [labelMode] so the decision is testable on the JVM.
 */
internal enum class LabelMode { FULL, ELLIPSIS, ICON_ONLY }

// Width thresholds below come from the P-3 measurement on emulator-5554 (1080x1920, 480dpi,
// 1dp = 3px), not from taste. A ten-character label was found 31px wide and 432px tall, which is
// the letter-per-line collapse this guard exists to stop; the sibling labels that stayed healthy
// measured 104px to 133px wide for 8 to 10 characters.

// 104px over 8 characters is 4.3dp of advance per glyph and 133px over 9 is 4.9dp, so a label
// needs about this many dp per character before it can be called a full fit.
private const val DP_PER_LABEL_CHAR = 5

// Width a slot spends on an icon, its spacer and content padding before any text can be drawn
// (measured ribbon buttons pair an 18dp icon with a 6dp spacer inside padded buttons).
private const val LABEL_CHROME_DP = 24

// The failure signature of P-3 is a label under 60px wide; 60px at 480dpi is 20dp. Below that an
// ellipsis cannot even be drawn, so only an icon with a tooltip is left.
private const val ICON_ONLY_CEILING_DP = 20

// A label counts as FULL only when it clears its own estimate by this much; the narrowest healthy
// label measured (104px) sits right on the edge, so a bare fit is treated as ELLIPSIS instead.
private const val FULL_SLACK_DP = 4

/**
 * Pure decision: given a row of [itemCount] equal slots inside [availableDp] dp, does a label of
 * [labelChars] characters get drawn whole, drawn with an ellipsis, or dropped for an icon?
 *
 * Unknown or empty geometry ([availableDp] <= 0, [itemCount] <= 0, [labelChars] <= 0) answers FULL:
 * the caller gets the single-line guard of [AdaptiveLabel] without any shortening of its own copy.
 */
internal fun labelMode(availableDp: Int, itemCount: Int, labelChars: Int): LabelMode {
    if (availableDp <= 0 || itemCount <= 0 || labelChars <= 0) return LabelMode.FULL
    val labelDp = availableDp / itemCount - LABEL_CHROME_DP
    if (labelDp <= ICON_ONLY_CEILING_DP) return LabelMode.ICON_ONLY
    val neededDp = labelChars * DP_PER_LABEL_CHAR
    return if (labelDp >= neededDp + FULL_SLACK_DP) LabelMode.FULL else LabelMode.ELLIPSIS
}

/**
 * Line budget for a mode. A label never gets more than one line once its slot is too tight, which
 * is exactly what turns a ten-character word into ten one-letter lines.
 */
internal fun labelMaxLines(mode: LabelMode, preferredMaxLines: Int = 1): Int = when (mode) {
    LabelMode.FULL -> preferredMaxLines.coerceAtLeast(1)
    LabelMode.ELLIPSIS -> 1
    LabelMode.ICON_ONLY -> 1
}

/**
 * The shared guard every button, chip and tab label goes through. It clamps the label to
 * [maxLines] (one line by default), ellipsizes instead of breaking mid-word, keeps soft wrapping
 * on, and aligns RTL the way [BidiText] does.
 *
 * Body text is untouched: this is opt-in, one call site at a time. [availableDp] is the width the
 * label really has; leave it at 0 to fall back to the window width, and tell [itemCount] how many
 * equal slots share it (bottom bars and weighted rows) so [labelMode] can degrade to ELLIPSIS or
 * ICON_ONLY on narrow screens. No existing string is rewritten, shortened or rephrased here.
 */
@Composable
fun AdaptiveLabel(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    availableDp: Int = 0,
    itemCount: Int = 1,
    direction: AppTextDirection = AppTextDirection.MIXED,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    val widthDp = if (availableDp > 0) availableDp else LocalConfiguration.current.screenWidthDp
    val mode = labelMode(widthDp, itemCount, text.length)

    // Alignment and direction ride on the style, exactly like BidiText does, because the Material3
    // Text overload has no textDirection parameter of its own.
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontWeight = fontWeight,
        fontSize = fontSize,
        maxLines = labelMaxLines(mode, maxLines),
        overflow = TextOverflow.Ellipsis,
        softWrap = true,
        style = style.copy(
            textAlign = bidiTextAlign(direction, text),
            textDirection = bidiTextDirection(direction)
        )
    )
}

/**
 * Lightweight inline markdown parser for bold, italic, and inline code.
 */
fun parseMarkdownInline(input: String): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        val length = input.length

        while (cursor < length) {
            when {
                // Bold: **text**
                input.startsWith("**", cursor) -> {
                    val end = input.indexOf("**", cursor + 2)
                    if (end != -1) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        append(input.substring(cursor + 2, end))
                        pop()
                        cursor = end + 2
                    } else {
                        append(input[cursor])
                        cursor++
                    }
                }
                // Inline Code: `code`
                input[cursor] == '`' -> {
                    val end = input.indexOf('`', cursor + 1)
                    if (end != -1) {
                        pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = Color(0x336366F1),
                                color = Color(0xFF38BDF8),
                                fontSize = 13.sp
                            )
                        )
                        append(input.substring(cursor + 1, end))
                        pop()
                        cursor = end + 1
                    } else {
                        append(input[cursor])
                        cursor++
                    }
                }
                // Italic: *text*
                input[cursor] == '*' -> {
                    val end = input.indexOf('*', cursor + 1)
                    if (end != -1) {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(input.substring(cursor + 1, end))
                        pop()
                        cursor = end + 1
                    } else {
                        append(input[cursor])
                        cursor++
                    }
                }
                else -> {
                    append(input[cursor])
                    cursor++
                }
            }
        }
    }
}
