package com.voicecaption.ui.screens

import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicecaption.ui.theme.Amber
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.voicecaption.R
import com.voicecaption.ui.state.OnboardingStep
import com.voicecaption.ui.theme.Spacing

/** Number of permission steps shown in the "Step n of 3" indicator (the intro is not a step). */
private const val STEP_COUNT = 3

/** What the onboarding buttons do; wired by AppRoot. */
class OnboardingActions(
    val onContinueFromIntro: () -> Unit,
    val onOpenOverlaySettings: () -> Unit,
    val onRequestNotifications: () -> Unit,
    val onSkipNotifications: () -> Unit,
    val onRequestMicrophone: () -> Unit,
    val onOpenAppSettings: () -> Unit,
)

/**
 * First-run onboarding, one step per screen. [denied] says the user already refused the current
 * permission, so the screen explains what will not work and offers "Open settings". The actions
 * are pinned to the bottom, in thumb reach.
 */
@Composable
fun OnboardingScreen(step: OnboardingStep, granted: Boolean, denied: Boolean, actions: OnboardingActions) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (step) {
                OnboardingStep.INTRO -> IntroStep(actions)
                OnboardingStep.OVERLAY -> PermissionStep(
                    number = 1, granted = granted, denied = denied,
                    icon = R.drawable.ic_pip,
                    title = R.string.onboarding_overlay_title,
                    body = R.string.onboarding_overlay_body,
                    deniedText = R.string.onboarding_overlay_denied,
                    action = R.string.onboarding_overlay_action,
                    onAction = actions.onOpenOverlaySettings,
                    onOpenSettings = actions.onOpenAppSettings,
                    showOpenSettings = false,
                    actionOpensSettings = true,
                )
                OnboardingStep.NOTIFICATIONS -> PermissionStep(
                    number = 2, granted = granted, denied = denied,
                    icon = R.drawable.ic_notification_bell,
                    title = R.string.onboarding_notifications_title,
                    body = R.string.onboarding_notifications_body,
                    deniedText = R.string.onboarding_notifications_denied,
                    action = R.string.onboarding_notifications_action,
                    onAction = actions.onRequestNotifications,
                    onOpenSettings = actions.onOpenAppSettings,
                    skip = R.string.onboarding_notifications_skip,
                    onSkip = actions.onSkipNotifications,
                )
                OnboardingStep.MICROPHONE -> PermissionStep(
                    number = 3, granted = granted, denied = denied,
                    icon = R.drawable.ic_graphic_eq,
                    title = R.string.onboarding_mic_title,
                    body = R.string.onboarding_mic_body,
                    deniedText = R.string.onboarding_mic_denied,
                    action = R.string.onboarding_mic_action,
                    onAction = actions.onRequestMicrophone,
                    onOpenSettings = actions.onOpenAppSettings,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.IntroStep(actions: OnboardingActions) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(start = Spacing.Large, end = Spacing.Large, top = Spacing.XXLarge),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Image(painterResource(R.drawable.ic_logo_mark), contentDescription = null, modifier = Modifier.size(88.dp))
        Text(
            stringResource(R.string.onboarding_intro_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.onboarding_intro_body), style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = Spacing.Margin, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.onboarding_intro_drm),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Column(Modifier.padding(start = Spacing.Margin, end = Spacing.Margin, bottom = Spacing.Large)) {
        Button(
            onClick = actions.onContinueFromIntro,
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.PrimaryButton),
        ) { Text(stringResource(R.string.action_continue), style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun ColumnScope.PermissionStep(
    number: Int,
    granted: Boolean,
    denied: Boolean,
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes body: Int,
    @StringRes deniedText: Int,
    @StringRes action: Int,
    onAction: () -> Unit,
    onOpenSettings: () -> Unit,
    showOpenSettings: Boolean = true,
    actionOpensSettings: Boolean = false,
    @StringRes skip: Int? = null,
    onSkip: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val refused = denied && !granted
    Column(
        modifier = Modifier.padding(start = Spacing.Large, end = Spacing.Large, top = Spacing.Margin),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.onboarding_step_indicator, number, STEP_COUNT),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(STEP_COUNT) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (index < number) scheme.primary else scheme.surfaceVariant),
                )
            }
        }
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(start = Spacing.Large, end = Spacing.Large, top = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(Amber),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = scheme.background, modifier = Modifier.size(32.dp))
        }
        Text(
            stringResource(title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
        if (refused) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, scheme.error, RoundedCornerShape(16.dp))
                    .padding(horizontal = Spacing.Margin, vertical = 14.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = scheme.error, modifier = Modifier.size(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Mini)) {
                    Text(
                        stringResource(R.string.onboarding_status_not_granted),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(stringResource(deniedText), style = MaterialTheme.typography.bodyMedium, color = scheme.error)
                }
            }
        } else {
            val (statusIcon, statusText, tint) = if (granted) {
                Triple(Icons.Filled.CheckCircle, R.string.onboarding_status_granted, scheme.primary)
            } else {
                Triple(Icons.Filled.Warning, R.string.onboarding_status_not_granted, scheme.onSurfaceVariant)
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(scheme.surfaceVariant)
                    .padding(start = 10.dp, end = 14.dp, top = Spacing.Small, bottom = Spacing.Small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                Icon(statusIcon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                Text(stringResource(statusText), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Column(
        modifier = Modifier.padding(start = Spacing.Margin, end = Spacing.Margin, bottom = Spacing.Large),
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
    ) {
        Button(
            onClick = onAction,
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.PrimaryButton),
        ) {
            Text(stringResource(action), style = MaterialTheme.typography.titleMedium)
            if (actionOpensSettings) {
                Spacer(Modifier.width(Spacing.Small))
                Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        if (refused && showOpenSettings) {
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.Target),
            ) { Text(stringResource(R.string.action_open_settings)) }
        }
        if (skip != null) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.Target),
            ) { Text(stringResource(skip)) }
        }
    }
}
