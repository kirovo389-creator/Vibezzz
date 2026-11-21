package com.novachat.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val Bg = Color(0xFF171717)
private val Panel = Color(0xFF222222)
private val Panel2 = Color(0xFF2B2B2B)
private val Ink = Color(0xFFF1EEE8)
private val Muted = Color(0xFF9B9892)
private val Accent = Color(0xFFD98A70)

data class ChatMessage(
    val role: String,
    val content: String,
    val reasoning: String = ""
)

data class ApiConfig(
    val key: String = "",
    val baseUrl: String = "",
    val model: String = ""
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Bg,
                    surface = Panel,
                    primary = Accent,
                    onBackground = Ink,
                    onSurface = Ink
                )
            ) {
                NovaChatApp(
                    context = this,
                    onSend = { config, history, onUpdate, onDone ->
                        streamReply(config, history, onUpdate, onDone)
                    }
                )
            }
        }
    }

    private fun streamReply(
        config: ApiConfig,
        history: List<ChatMessage>,
        onUpdate: (String, String) -> Unit,
        onDone: (String?) -> Unit
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val base = config.baseUrl.trim().trimEnd('/')
                val endpoint = when {
                    base.endsWith("/chat/completions") -> base
                    base.endsWith("/v1") -> "$base/chat/completions"
                    else -> "$base/v1/chat/completions"
                }

                val messages = JSONArray()
                history.forEach { item ->
                    val body = if (item.reasoning.isNotBlank()) {
                        "<think>${item.reasoning}</think>${item.content}"
                    } else item.content
                    messages.put(JSONObject().put("role", item.role).put("content", body))
                }

                val payload = JSONObject()
                    .put("model", config.model)
                    .put("messages", messages)
                    .put("stream", true)

                val request = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer ${config.key}")
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val error = response.body?.string()?.take(500) ?: "HTTP ${response.code}"
                        throw IllegalStateException("Ошибка API (${response.code}): $error")
                    }
                    val source = response.body?.source()
                        ?: throw IllegalStateException("Пустой ответ сервера")
                    var answer = ""
                    var thoughts = ""
                    var raw = ""
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: continue
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        try {
                            val delta = JSONObject(data)
                                .optJSONArray("choices")?.optJSONObject(0)
                                ?.optJSONObject("delta") ?: continue
                            val chunk = delta.optString("content", "")
                            val thinkChunk = delta.optString("reasoning_content",
                                delta.optString("reasoning", ""))
                            if (thinkChunk.isNotBlank()) thoughts += thinkChunk
                            if (chunk.isNotBlank()) raw += chunk
                            val parsed = splitThinking(raw)
                            answer = parsed.first
                            if (parsed.second.isNotBlank()) thoughts += parsed.second
                            withContext(Dispatchers.Main) { onUpdate(answer, thoughts) }
                        } catch (_: Exception) {
                            // Ignore malformed keep-alive frames.
                        }
                    }
                    val parsed = splitThinking(raw)
                    answer = parsed.first
                    if (parsed.second.isNotBlank()) thoughts += parsed.second
                    withContext(Dispatchers.Main) { onUpdate(answer, thoughts) }
                }
                withContext(Dispatchers.Main) { onDone(null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onDone(e.message ?: "Не удалось выполнить запрос") }
            }
        }
    }
}

private fun splitThinking(raw: String): Pair<String, String> {
    val start = raw.indexOf("<think>")
    val end = raw.indexOf("</think>")
    return if (start >= 0 && end > start) {
        (raw.removeRange(start, end + 8)).trim() to raw.substring(start + 7, end).trim()
    } else raw to ""
}

private fun loadConfig(context: Context): ApiConfig {
    return try {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(
            context, "nova_secure_settings", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        ApiConfig(
            prefs.getString("api_key", "") ?: "",
            prefs.getString("base_url", "") ?: "",
            prefs.getString("model", "") ?: ""
        )
    } catch (_: Exception) { ApiConfig() }
}

private fun saveConfig(context: Context, config: ApiConfig) {
    val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    val prefs = EncryptedSharedPreferences.create(
        context, "nova_secure_settings", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    prefs.edit()
        .putString("api_key", config.key)
        .putString("base_url", config.baseUrl)
        .putString("model", config.model)
        .apply()
}

@Composable
private fun NovaChatApp(
    context: Context,
    onSend: (ApiConfig, List<ChatMessage>, (String, String) -> Unit, (String?) -> Unit) -> Unit
) {
    var config by remember { mutableStateOf(loadConfig(context)) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDrawer by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(showDrawer) {
        if (showDrawer) drawerState.open() else drawerState.close()
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content, messages.lastOrNull()?.reasoning) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Panel,
                modifier = Modifier.width(300.dp)
            ) {
                Spacer(Modifier.height(28.dp))
                Text("Nova Chat", modifier = Modifier.padding(horizontal = 22.dp), fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(26.dp))
                NavigationDrawerItem(
                    label = { Text("＋  Новый чат") },
                    selected = false,
                    onClick = { messages.clear(); error = null; showDrawer = false },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                )
                Spacer(Modifier.height(18.dp))
                Text("Недавние чаты", color = Muted, modifier = Modifier.padding(horizontal = 22.dp), fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Text("История будет добавлена в следующем обновлении", color = Muted, modifier = Modifier.padding(horizontal = 22.dp), fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                NavigationDrawerItem(
                    label = { Text("⚙  Настройки API") },
                    selected = false,
                    onClick = { showDrawer = false; showSettings = true },
                    modifier = Modifier.padding(12.dp),
                    colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                )
            }
        },
        gesturesEnabled = showDrawer
    ) {
        Column(Modifier.fillMaxSize().background(Bg)) {
            Row(
                Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { showDrawer = true }) {
                    Icon(Icons.Default.Menu, contentDescription = "Меню", tint = Ink)
                }
                Text(
                    if (messages.isEmpty()) "Новый чат" else "Nova Chat",
                    color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = Ink)
                }
            }

            if (messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("✳", color = Accent, fontSize = 48.sp)
                        Spacer(Modifier.height(14.dp))
                        Text("С чего начнём?", color = Ink, fontSize = 28.sp, fontFamily = FontFamily.Serif)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (config.key.isBlank()) "Сначала добавь API в настройках" else "Задай вопрос или начни новую идею",
                            color = Muted, fontSize = 14.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    itemsIndexed(messages) { index, message ->
                        if (message.role == "user") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Text(
                                    message.content,
                                    color = Ink,
                                    modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(Panel2)
                                        .padding(horizontal = 17.dp, vertical = 13.dp).widthIn(max = 290.dp),
                                    fontSize = 16.sp
                                )
                            }
                        } else {
                            Column(Modifier.fillMaxWidth().animateContentSize()) {
                                if (message.reasoning.isNotBlank()) ReasoningBlock(message.reasoning)
                                SelectionContainer {
                                    Text(
                                        message.content.ifBlank { if (busy && index == messages.lastIndex) " " else "" },
                                        color = Ink, fontSize = 17.sp, lineHeight = 27.sp,
                                        fontFamily = FontFamily.Serif
                                    )
                                }
                            }
                        }
                    }
                    if (busy) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
                                Text("Думаю…", color = Muted, fontSize = 14.sp)
                            }
                        }
                    }
                    if (error != null) item {
                        Text(error!!, color = Color(0xFFFF9A8E), fontSize = 13.sp)
                    }
                }
            }

            Surface(
                color = Panel,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    BasicTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 46.dp).padding(horizontal = 8.dp, vertical = 10.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 16.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                        decorationBox = { inner ->
                            Box {
                                if (prompt.isEmpty()) Text("Напишите сообщение…", color = Muted, fontSize = 16.sp)
                                inner()
                            }
                        }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Настройки", tint = Muted)
                        }
                        IconButton(
                            enabled = prompt.isNotBlank() && !busy,
                            onClick = {
                                val text = prompt.trim()
                                if (config.key.isBlank() || config.baseUrl.isBlank() || config.model.isBlank()) {
                                    showSettings = true
                                } else if (text.isNotEmpty()) {
                                    prompt = ""
                                    error = null
                                    messages.add(ChatMessage("user", text))
                                    messages.add(ChatMessage("assistant", ""))
                                    busy = true
                                    val index = messages.lastIndex
                                    onSend(config, messages.dropLast(1).toList(), { answer, thoughts ->
                                        if (index < messages.size) messages[index] = ChatMessage("assistant", answer, thoughts)
                                    }, { failure ->
                                        busy = false
                                        if (failure != null) error = failure
                                    })
                                }
                            },
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(if (prompt.isNotBlank() && !busy) Ink else Panel2)
                        ) {
                            Icon(if (busy) Icons.Default.Stop else Icons.Default.ArrowUpward, contentDescription = "Отправить",
                                tint = if (prompt.isNotBlank() && !busy) Bg else Muted)
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            initial = config,
            onDismiss = { showSettings = false },
            onSave = {
                config = it
                saveConfig(context, it)
                showSettings = false
            }
        )
    }
}

@Composable
private fun ReasoningBlock(reasoning: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).animateContentSize()) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(if (expanded) "⌄  Ход рассуждений" else "›  Ход рассуждений", color = Muted, fontSize = 14.sp)
        }
        AnimatedVisibility(visible = expanded) {
            Text(reasoning, color = Muted, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun SettingsDialog(
    initial: ApiConfig,
    onDismiss: () -> Unit,
    onSave: (ApiConfig) -> Unit
) {
    var key by remember { mutableStateOf(initial.key) }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = Panel,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("Подключение модели", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Данные хранятся на устройстве в зашифрованном виде.", color = Muted, fontSize = 13.sp)
                ConfigField("API Key", key, { key = it }, secret = true)
                ConfigField("Base URL", url, { url = it }, hint = "https://example.com/v1")
                ConfigField("Model ID", model, { model = it }, hint = "например, gpt-4o-mini")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Отмена", color = Muted) }
                    Button(
                        onClick = { onSave(ApiConfig(key.trim(), url.trim(), model.trim())) },
                        enabled = key.isNotBlank() && url.isNotBlank() && model.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Bg)
                    ) { Text("Сохранить") }
                }
            }
        }
    }
}

@Composable
private fun ConfigField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String = "",
    secret: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(hint, color = Muted) },
            singleLine = true,
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Ink,
                unfocusedTextColor = Ink,
                focusedBorderColor = Accent,
                unfocusedBorderColor = Panel2,
                cursorColor = Accent
            )
        )
    }
}
