package com.bitchat.ui.groups

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.bitchat.mesh.GroupInvite

/**
 * Asks the user before joining a group from an online invite. Accepting replaces the current
 * group (the app is one-group-at-a-time), so it must be an explicit choice.
 */
@Composable
fun GroupInviteDialog(
    invite: GroupInvite,
    onJoin: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Group invitation") },
        text = {
            Text(
                "${invite.inviterName} invited you to join \"${invite.groupName}\". " +
                    "Joining replaces your current group."
            )
        },
        confirmButton = {
            TextButton(onClick = onJoin) { Text("Join") }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text("Decline") }
        },
    )
}
