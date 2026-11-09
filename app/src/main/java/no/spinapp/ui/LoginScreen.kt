package no.spinapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.spinapp.AppState
import no.spinapp.AuthManager
import no.spinapp.core.AuthException
import no.spinapp.core.EmailOtpAuth

/** To steg: skriv e-post → skriv koden fra e-posten. */
@Composable
fun LoginScreen() {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(AppState.lastEmail ?: "") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cooldown by remember { mutableIntStateOf(0) }
    val codeFocus = remember { FocusRequester() }

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown--
        }
    }

    fun send() {
        if (busy) return
        error = null
        if (EmailOtpAuth.normalizeEmail(email) == null) {
            error = "Skriv inn en gyldig e-postadresse"
            return
        }
        busy = true
        scope.launch {
            try {
                AuthManager.sendCode(email)
                codeSent = true
                code = ""
                cooldown = 60
            } catch (e: AuthException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    fun verify() {
        if (busy) return
        error = null
        busy = true
        scope.launch {
            try {
                AuthManager.verify(email, code)
            } catch (e: AuthException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text("SpinApp", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(
            "Logg inn med e-posten din. Du får en kode på e-post. Ingen passord, og ikke noe navn.",
            color = Color.Gray,
        )

        if (!codeSent) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.trim() },
                label = { Text("E-post") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { send() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (busy) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp) else Text("Send kode")
            }
        } else {
            Text("Vi har sendt en kode til", color = Color.Gray)
            Text(email, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = code,
                onValueChange = { v ->
                    code = v.filter(Char::isDigit).take(10)
                    if (code.length == 6 && !busy) verify()
                },
                label = { Text("Kode") },
                singleLine = true,
                textStyle = TextStyle(fontSize = 28.sp, fontFamily = FontFamily.Monospace, letterSpacing = 6.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { verify() }),
                modifier = Modifier.fillMaxWidth().focusRequester(codeFocus),
            )
            LaunchedEffect(Unit) { runCatching { codeFocus.requestFocus() } }
            Button(onClick = { verify() }, enabled = !busy && code.length >= 6, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (busy) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp) else Text("Logg inn")
            }
            TextButton(onClick = { send() }, enabled = cooldown == 0 && !busy) {
                Text(if (cooldown > 0) "Send ny kode om $cooldown s" else "Send ny kode")
            }
            TextButton(onClick = { codeSent = false; error = null }) { Text("Bruk en annen e-post") }
            Text("Finner du ikke e-posten? Sjekk søppelpost.", color = Color.Gray, fontSize = 13.sp)
        }

        error?.let { Text(it, color = Color(0xFFE74C3C)) }

        Spacer(Modifier.weight(1f))
        Text(
            "Vi lagrer bare e-postadressen din, slik at du kan logge inn. Treningsdataene ligger på telefonen.",
            color = Color.Gray, fontSize = 12.sp,
        )
    }
}
