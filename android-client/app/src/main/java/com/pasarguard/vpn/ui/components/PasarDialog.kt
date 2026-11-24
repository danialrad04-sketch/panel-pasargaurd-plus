package com.pasarguard.vpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PasarDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null
) {
    val palette = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(26.dp)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.border(1.dp, palette.primary.copy(alpha = .32f), shape),
        containerColor = palette.surface,
        shape = shape,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Spacer(Modifier.size(38.dp, 4.dp).background(palette.primary, RoundedCornerShape(2.dp)))
                title()
            }
        },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { text() } },
        confirmButton = confirmButton,
        dismissButton = dismissButton
    )
}

@Composable
fun PasarSheetHandle() {
    Spacer(
        Modifier.padding(vertical = 16.dp)
            .size(40.dp, 5.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = .65f), RoundedCornerShape(3.dp))
    )
}
