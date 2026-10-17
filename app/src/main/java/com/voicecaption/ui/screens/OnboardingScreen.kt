package com.voicecaption.ui.screens

import androidx.annotation.StringRes
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
 * permission, so the screen explains what will not work and offers "Open settings".
 */
@Composable
fun OnboardingScreen(step: OnboardingStep, granted: Boolean, denied: Boolean, actions: OnboardingActions) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.Margin),
            verticalArrangement = Arrangement.spacedBy(Spacing.Margin),
        ) {
            when (step) {
                OnboardingStep.INTRO -> IntroStep(actions)
                OnboardingStep.OVERLAY -> PermissionStep(
                    number = 1, granted = granted, denied = denied,
                    title = R.string.onboarding_overlay_title,
                    body = R.string.onboarding_overlay_body,
                    deniedText = R.string.onboarding_overlay_denied,
                    action = R.string.onboarding_overlay_action,
                    onAction = actions.onOpenOverlaySettings,
                    onOpenSettings = actions.onOpenAppSettings,
                    showOpenSettings = false,
                )
                OnboardingStep.NOTIFICATIONS -> PermissionStep(
                    number = 2, granted = granted, denied = denied,
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
private fun IntroStep(actions: OnboardingActions) {
    Text(
        stringResource(R.string.onboarding_intro_title),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    Text(stringResource(R.string.onboarding_intro_body), style = MaterialTheme.typography.bodyLarge)
    Text(
        stringResource(R.string.onboarding_intro_drm),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = actions.onContinueFromIntro,
        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.PrimaryButton),
    ) { Text(stringResource(R.string.action_continue)) }
}

@Composable
private fun PermissionStep(
    number: Int,
    granted: Boolean,
    denied: Boolean,
    @StringRes title: Int,
    @StringRes body: Int,
    @StringRes deniedText: Int,
    @StringRes action: Int,
    onAction: () -> Unit,
    onOpenSettings: () -> Unit,
    showOpenSettings: Boolean = true,
    @StringRes skip: Int? = null,
    onSkip: () -> Unit = {},
) {
    Text(
        stringResource(R.string.onboarding_step_indicator, number, STEP_COUNT),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Text(
        stringResource(title),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
    if (granted) {
        StatusLine(
            Icons.Filled.CheckCircle,
            stringResource(R.string.onboarding_status_granted),
            tint = MaterialTheme.colorScheme.primary,
        )
    } else {
        StatusLine(
            Icons.Filled.Warning,
            stringResource(R.string.onboarding_status_not_granted),
            tint = if (denied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (denied && !granted) {
        Text(
            stringResource(deniedText),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Button(
        onClick = onAction,
        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.PrimaryButton),
    ) { Text(stringResource(action)) }
    if (denied && !granted && showOpenSettings) {
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
