package com.missingtable.scorer.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.missingtable.scorer.AppContainer
import com.missingtable.scorer.data.api.LoginRequest
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(container: AppContainer, onLoggedIn: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("MT Scorer", style = MaterialTheme.typography.headlineLarge)
        Spacer24()
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer24()
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer24()
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
            Spacer24()
        }
        Button(
            onClick = {
                loading = true
                error = null
                scope.launch {
                    runCatching {
                        val resp = container.api.login(LoginRequest(username.trim(), password))
                        val access = resp.accessToken ?: error("No token in response")
                        container.tokenStore.save(access, resp.refreshToken, username.trim())
                        container.refreshSession()
                    }.onSuccess {
                        loading = false
                        onLoggedIn()
                    }.onFailure { e ->
                        loading = false
                        error = when {
                            e is retrofit2.HttpException && e.code() == 401 ->
                                "Login failed — check credentials"
                            else -> "Can't reach server: ${e.message ?: e::class.simpleName}"
                        }
                    }
                }
            },
            enabled = !loading && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.height(24.dp)) else Text("Sign in")
        }
        Spacer24()
        Text(
            "v${com.missingtable.scorer.BuildConfig.VERSION_NAME} " +
                "(build ${com.missingtable.scorer.BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Spacer24() = androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
