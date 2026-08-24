package com.carlom.klardrop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.carlom.klardrop.theme.KdTheme
import com.klardrop.common.CrashReporter
import com.klardrop.common.ReportOutcome

/**
 * "Report a problem" — sends what the user typed to Sentry as user feedback, which drags the last
 * 100 log breadcrumbs along with it (see [CrashReporter.reportUserFeedback]).
 *
 * This exists because the interesting failures do not crash. A transfer that would not connect
 * leaves nothing behind for a crash reporter to find, and a bare "it didn't work" report is not
 * actionable — the log tail leading up to it is the whole value.
 *
 * Two wrappers, one form: [ReportProblemSection] folds into the phone settings sheet,
 * [ReportProblemDialog] is the desktop window pattern (sheets are a touch idiom).
 */
@Composable
internal fun ReportProblemSection() {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing
    var expanded by remember { mutableStateOf(false) }

    if (expanded) {
        ReportProblemForm(onDone = { expanded = false })
        return
    }

    Column {
        Text(
            text = "Report a problem",
            style = typography.body.copy(color = colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = spacing.s2),
        )
        Text(
            text = "Sends your description with the recent activity log so we can see what went wrong.",
            style = typography.caption.copy(color = colors.text2),
        )
    }
}

@Composable
internal fun ReportProblemDialog(onDismiss: () -> Unit) {
    val colors = KdTheme.colors
    val spacing = KdTheme.spacing
    val radii = KdTheme.radii

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(420.dp)
                .clip(radii.shapeLg)
                .background(colors.bg1)
                .border(width = 1.dp, color = colors.border, shape = radii.shapeLg)
                .padding(spacing.s6),
        ) {
            ReportProblemForm(onDone = onDismiss)
        }
    }
}

@Composable
private fun ReportProblemForm(onDone: () -> Unit) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing

    var description by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<ReportOutcome?>(null) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.accent,
        unfocusedBorderColor = colors.border,
        cursorColor = colors.accent,
    )

    Column(verticalArrangement = Arrangement.spacedBy(spacing.s3)) {
        Text(
            text = "Report a problem",
            style = typography.headline.copy(color = colors.text),
        )
        Text(
            text = "Describe what happened. The recent activity log is attached automatically.",
            style = typography.caption.copy(color = colors.text2),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("What went wrong?", style = typography.caption.copy(color = colors.text2)) },
            textStyle = typography.body.copy(color = colors.text),
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            colors = fieldColors,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email (optional)", style = typography.caption.copy(color = colors.text2)) },
            textStyle = typography.body.copy(color = colors.text),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = fieldColors,
        )

        outcome?.let { result ->
            Text(
                text = when (result) {
                    ReportOutcome.Sent -> "Thanks — report sent."
                    ReportOutcome.Disabled -> "Reporting is turned off in this build, so nothing was sent."
                    ReportOutcome.Failed -> "Could not send the report. Please try again later."
                },
                style = typography.caption.copy(
                    color = if (result == ReportOutcome.Sent) colors.text2 else colors.err,
                ),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.s2, Alignment.End),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onDone) {
                Text(
                    text = if (outcome == ReportOutcome.Sent) "Close" else "Cancel",
                    style = typography.body.copy(color = colors.text2),
                )
            }
            TextButton(
                enabled = description.isNotBlank(),
                onClick = { outcome = CrashReporter.reportUserFeedback(description, email = email) },
            ) {
                Text(
                    text = "Send",
                    style = typography.body.copy(
                        color = if (description.isBlank()) colors.text3 else colors.accent,
                    ),
                )
            }
        }
    }
}
