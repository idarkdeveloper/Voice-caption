package com.voicecaption.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicecaption.R
import com.voicecaption.caption.CaptionFormatter
import com.voicecaption.core.Caption
import com.voicecaption.overlay.OverlayMath
import com.voicecaption.ui.theme.Spacing

private val SampleCaption = Caption(
    id = 1,
    sourceText = "Hola, ¿cómo estás?",
    translatedText = "Hi, how are you?",
    isFinal = true,
    updatedAtMs = 0,
)

/** Stand-in for video behind the plate so the opacity is visible. Same in both themes. */
private val BackdropTop = Color(0xFF6B7A8F)
private val BackdropBottom = Color(0xFF2E3A4A)

/** Source line is drawn at this fraction of the caption size, as in the overlay. */
private const val SOURCE_LINE_SCALE = 0.8f

/**
 * Live preview of the overlay plate: black at the chosen opacity (floored like the real overlay),
 * white text, 8 dp radius, size and bilingual lines as set.
 */
@Composable
fun CaptionPreview(fontSizeSp: Int, opacity: Float, bilingual: Boolean, modifier: Modifier = Modifier) {
    val lines = remember(bilingual) { CaptionFormatter.lines(listOf(SampleCaption), bilingual) }
    val plate = Color.Black.copy(alpha = OverlayMath.plateAlpha(opacity) / 255f)
    val description = stringResource(R.string.settings_preview_description, lines.joinToString(". "))
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Spacing.Small))
            .background(Brush.verticalGradient(listOf(BackdropTop, BackdropBottom)))
            .padding(vertical = Spacing.Margin)
            .heightIn(min = 120.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .clearAndSetSemantics { }
                .widthIn(max = maxWidth * OverlayMath.MAX_WIDTH_FRACTION)
                .clip(RoundedCornerShape(Spacing.Small))
                .background(plate)
                .padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
        ) {
            lines.forEachIndexed { index, line ->
                val isSource = bilingual && lines.size > 1 && index == 0
                Text(
                    line,
                    color = Color.White,
                    fontSize = (if (isSource) fontSizeSp * SOURCE_LINE_SCALE else fontSizeSp.toFloat()).sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
