package com.voicecaption.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.Dp
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
 * Live preview of the overlay: the translation plate and, with "show original text", a smaller
 * original-language plate above it. Plate colour at the chosen opacity (floored like the real
 * overlay), text colour, 8 dp radius, size as set.
 */
@Composable
fun CaptionPreview(
    fontSizeSp: Int,
    opacity: Float,
    bilingual: Boolean,
    textColor: Int,
    plateColor: Int,
    modifier: Modifier = Modifier,
) {
    val plates = remember(bilingual) { CaptionFormatter.plates(listOf(SampleCaption), bilingual) }
    val plateBackground = Color(plateColor).copy(alpha = OverlayMath.plateAlpha(opacity) / 255f)
    val text = Color(textColor)
    val description = stringResource(
        R.string.settings_preview_description,
        (plates.source + plates.translation).joinToString(". "),
    )
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
        val available = maxWidth
        Column(
            modifier = Modifier.clearAndSetSemantics { },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Small / 2),
        ) {
            if (plates.source.isNotEmpty()) {
                PreviewPlate(plates.source, fontSizeSp * SOURCE_LINE_SCALE, plateBackground, text, available, dimmed = true)
            }
            if (plates.translation.isNotEmpty()) {
                PreviewPlate(plates.translation, fontSizeSp.toFloat(), plateBackground, text, available, dimmed = false)
            }
        }
    }
}

@Composable
private fun PreviewPlate(lines: List<String>, sizeSp: Float, color: Color, textColor: Color, available: Dp, dimmed: Boolean) {
    Column(
        modifier = Modifier
            .widthIn(max = available * OverlayMath.MAX_WIDTH_FRACTION)
            .clip(RoundedCornerShape(Spacing.Small))
            .background(color)
            .padding(horizontal = Spacing.Medium, vertical = Spacing.Small),
    ) {
        lines.forEach { line ->
            Text(
                line,
                color = if (dimmed) textColor.copy(alpha = 0.85f) else textColor,
                fontSize = sizeSp.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
