package com.bitchat.ui.lock

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object AppLock {

    private const val PREFS = "bitchat_security"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_HASH = "pin_hash"

    @Volatile
    private var unlockedInSession = false

    private val random = SecureRandom()

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_HASH)

    fun isUnlocked(): Boolean = unlockedInSession

    fun unlock() {
        unlockedInSession = true
    }

    fun lock() {
        unlockedInSession = false
    }

    fun setPin(context: Context, pin: String) {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val hash = hash(pin, salt)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
        unlock()
    }

    fun clearPin(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_SALT)
            .remove(KEY_HASH)
            .apply()
    }

    fun verify(context: Context, pin: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saltB64 = prefs.getString(KEY_SALT, null) ?: return false
        val expectedB64 = prefs.getString(KEY_HASH, null) ?: return false
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expected = Base64.decode(expectedB64, Base64.NO_WRAP)
        return MessageDigest.isEqual(hash(pin, salt), expected)
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 10_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}

@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Ghostwire",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "App is locked",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = {
                    pin = it.filter(Char::isDigit).take(8)
                    error = null
                },
                label = { Text("PIN") },
                isError = error != null,
                supportingText = { Text(error ?: "") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (AppLock.verify(context, pin)) {
                        AppLock.unlock()
                        onUnlocked()
                    } else {
                        error = "Wrong PIN"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Unlock")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showResetConfirm = true }) {
                Text("Reset lock")
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset lock?") },
            text = { Text("This removes the PIN entirely. Anyone with the phone can then open the app.") },
            confirmButton = {
                TextButton(onClick = {
                    AppLock.clearPin(context)
                    showResetConfirm = false
                    pin = ""
                    onUnlocked()
                }) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val enabled = AppLock.isEnabled(context)
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (enabled) "Change app lock" else "Set app lock") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (enabled) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { currentPin = it.filter(Char::isDigit).take(8); error = null },
                        label = { Text("Current PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { newPin = it.filter(Char::isDigit).take(8); error = null },
                    label = { Text("New PIN (4-8 digits)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it.filter(Char::isDigit).take(8); error = null },
                    label = { Text("Confirm PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (enabled && !AppLock.verify(context, currentPin)) {
                    error = "Current PIN is wrong"
                } else if (newPin.length < 4) {
                    error = "PIN must be 4-8 digits"
                } else if (newPin != confirmPin) {
                    error = "PINs do not match"
                } else {
                    AppLock.setPin(context, newPin)
                    onDismiss()
                }
            }) {
                Text(if (enabled) "Save" else "Set lock")
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (enabled) {
                    TextButton(onClick = { showRemoveConfirm = true }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                } else {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        },
    )

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove app lock?") },
            text = { Text("The PIN will be deleted and the app will open without a lock.") },
            confirmButton = {
                TextButton(onClick = {
                    AppLock.clearPin(context)
                    showRemoveConfirm = false
                    onDismiss()
                }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}