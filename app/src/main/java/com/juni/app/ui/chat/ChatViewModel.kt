package com.juni.app.ui.chat

import android.net.Uri
import android.util.Base64
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juni.app.JuniApp
import com.juni.app.data.db.ConversationEntity
import com.juni.app.data.prefs.ProviderId
import com.juni.app.data.provider.AiProvider
import com.juni.app.data.provider.ProviderRegistry
import com.juni.app.data.vault.VaultRepository
import com.juni.app.domain.agent.AgentEvent
import com.juni.app.domain.agent.AgentLoop
import com.juni.app.domain.agent.ApprovalResult
import com.juni.app.domain.agent.ChatIntent
import com.juni.app.domain.agent.Message
import com.juni.app.domain.agent.MessageContent
import com.juni.app.domain.agent.Role
import com.juni.app.domain.tools.AttachmentStaging
import com.juni.app.domain.tools.ToolRegistry
import com.juni.app.domain.tools.VaultTools
import com.juni.app.ui.terminal.Toaster
import com.juni.app.ui.terminal.randomThinkingWord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ChatUi(
    val items: List<ChatItem> = emptyList(),
    val streaming: String = "",
    val isStreaming: Boolean = false,
    val statusLine: String = "",
    val thinkingWord: String = "thinking",
    val title: String = "",
    val vaultUri: String? = null,
    val pendingIntent: ChatIntent? = null,
)

private val AUTO_APPROVE = setOf(
    "list_files", "read_note", "search_notes", "ask_clarifying_question", "rename_chat",
)

private val VAULT_WRITE_TOOLS = setOf(
    "create_note", "edit_note", "move_note", "save_attachment",
)

class ChatViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {

    private val app = JuniApp.get()
    private val repo = app.conversationRepository
    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"]) {
        "Chat opened without a conversationId"
    }

    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    /** Canonical message history. The DB is the source of truth across launches. */
    private val history: MutableList<Message> = mutableListOf()

    /** Messages produced this turn that haven't been flushed to the DB yet. */
    private val pendingPersist: MutableList<Message> = mutableListOf()

    private val pendingApprovals: MutableMap<String, CompletableDeferred<ApprovalResult>> = mutableMapOf()
    private val attachmentStaging = AttachmentStaging()

    val pendingImages: StateFlow<List<ByteArray>> = app.composerImages

    private var currentJob: Job? = null
    private var conversation: ConversationEntity? = null

    init {
        viewModelScope.launch {
            conversation = repo.get(conversationId)
            val loaded = repo.loadMessages(conversationId)
            history.addAll(loaded)
            _ui.value = _ui.value.copy(
                items = messagesToChatItems(loaded),
                title = conversation?.title.orEmpty(),
            )
        }
        // Keep statusLine + vaultUri reactive — if the user clears the vault from
        // Settings mid-chat, the banner appears and the composer disables.
        viewModelScope.launch {
            app.appSettings.flow.collect { s ->
                _ui.value = _ui.value.copy(
                    statusLine = "${s.providerId.label} · ${s.modelByProvider[s.providerId]}",
                    vaultUri = s.vaultUri,
                )
            }
        }
    }

    fun send(input: String) {
        val rawText = input.trim()
        val intent = _ui.value.pendingIntent
        val text = if (intent != null) {
            buildString {
                append("[")
                append(intent.instruction())
                append("]")
                if (rawText.isNotEmpty()) {
                    append("\n\n")
                    append(rawText)
                }
            }
        } else {
            rawText
        }
        val images = app.composerImages.value
        if ((text.isEmpty() && images.isEmpty()) || _ui.value.isStreaming) return

        currentJob = viewModelScope.launch {
            val settings = app.appSettings.flow.first()
            if (settings.vaultUri == null) {
                val msg = "Set a vault folder in Settings before chatting."
                appendItem(ChatItem.SystemError(msg))
                Toaster.error(msg)
                return@launch
            }
            val resolution = ProviderRegistry.resolveFor(settings, app.securePrefs)
            if (resolution is ProviderRegistry.Resolution.Incomplete) {
                appendItem(ChatItem.SystemError(resolution.message))
                Toaster.error(resolution.message)
                return@launch
            }
            val ready = resolution as ProviderRegistry.Resolution.Ready

            // Build user message and persist it immediately (so it survives a mid-turn kill).
            val content = buildList<MessageContent> {
                images.forEach { bytes ->
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    add(MessageContent.Image(mimeType = "image/jpeg", base64 = b64))
                    attachmentStaging.add(bytes)
                }
                if (text.isNotEmpty()) add(MessageContent.Text(text))
            }
            val userMessage = Message(Role.USER, content)
            history += userMessage
            repo.appendMessage(conversationId, userMessage)

            // Display only the user-typed text, not the bracketed intent prefix.
            appendItem(ChatItem.UserMessage(text = rawText, images = images.toList()))
            app.clearComposerImages()
            _ui.value = _ui.value.copy(
                streaming = "",
                isStreaming = true,
                thinkingWord = randomThinkingWord(),
                pendingIntent = null,
            )

            try {
                runAgent(
                    provider = ready.provider,
                    model = ready.model,
                    vaultUri = settings.vaultUri,
                    systemPrompt = settings.systemPrompt,
                )
            } catch (t: Throwable) {
                val msg = t.message ?: "stream failed"
                appendItem(ChatItem.SystemError(msg))
                Toaster.error(msg.take(120))
                _ui.value = _ui.value.copy(streaming = "", isStreaming = false)
                flushPersist()
            }
        }
    }

    fun approve(toolUseId: String) {
        pendingApprovals.remove(toolUseId)?.complete(ApprovalResult.Approve)
        updateToolState(toolUseId, ToolState.Running)
    }

    fun reject(toolUseId: String, reason: String? = null) {
        pendingApprovals.remove(toolUseId)?.complete(ApprovalResult.Reject(reason))
        updateToolState(toolUseId, ToolState.Rejected(reason))
    }

    fun stop() {
        currentJob?.cancel()
        pendingApprovals.values.toList().forEach {
            it.complete(ApprovalResult.Reject("User cancelled."))
        }
        pendingApprovals.clear()
        _ui.value = _ui.value.copy(isStreaming = false, streaming = "")
        viewModelScope.launch { flushPersist() }
    }

    fun removePendingImage(index: Int) {
        val current = app.composerImages.value
        if (index !in current.indices) return
        app.composerImages.value = current.toMutableList().also { it.removeAt(index) }
    }

    fun setIntent(intent: ChatIntent) {
        _ui.value = _ui.value.copy(pendingIntent = intent)
    }

    fun clearIntent() {
        _ui.value = _ui.value.copy(pendingIntent = null)
    }

    fun rename(newTitle: String) {
        val title = newTitle.trim().ifEmpty { return }
        viewModelScope.launch {
            repo.setTitle(conversationId, title)
            _ui.value = _ui.value.copy(title = title)
            Toaster.success("renamed to \"${title.take(40)}\"")
        }
    }

    /** Called when the agent's rename_chat tool fires. Persists + updates UI. */
    private fun applyAgentRename(newTitle: String) {
        val title = newTitle.trim().ifEmpty { return }
        viewModelScope.launch {
            repo.setTitle(conversationId, title)
            _ui.value = _ui.value.copy(title = title)
        }
    }

    private suspend fun runAgent(
        provider: AiProvider,
        model: String,
        vaultUri: String?,
        systemPrompt: String,
    ) {
        val tools = if (vaultUri != null) {
            val vault = VaultRepository(app, Uri.parse(vaultUri))
            ToolRegistry(
                VaultTools.all(
                    vault = vault,
                    index = app.vaultIndex,
                    attachmentStaging = attachmentStaging,
                    onRenameChat = { newTitle -> applyAgentRename(newTitle) },
                ),
            )
        } else {
            ToolRegistry(emptyList())
        }
        val loop = AgentLoop(provider = provider, tools = tools, model = model, systemPrompt = systemPrompt)
        val streamed = StringBuilder()

        loop.run(
            initialMessages = history.toList(),
            approvalGate = { id, name, _ ->
                if (name in AUTO_APPROVE) {
                    updateToolState(id, ToolState.Running)
                    ApprovalResult.Approve
                } else {
                    val deferred = CompletableDeferred<ApprovalResult>()
                    pendingApprovals[id] = deferred
                    deferred.await()
                }
            },
        ).collect { event ->
            when (event) {
                is AgentEvent.TextDelta -> {
                    streamed.append(event.text)
                    _ui.value = _ui.value.copy(streaming = streamed.toString())
                }
                is AgentEvent.AssistantMessageDone -> {
                    history += event.message
                    pendingPersist += event.message
                    if (streamed.isNotEmpty()) {
                        appendItem(ChatItem.AssistantText(streamed.toString()))
                        streamed.clear()
                        _ui.value = _ui.value.copy(streaming = "")
                    }
                }
                is AgentEvent.ToolResultsBatch -> {
                    history += event.message
                    pendingPersist += event.message
                }
                is AgentEvent.PendingTool -> {
                    appendItem(
                        ChatItem.ToolCall(
                            id = event.id,
                            name = event.name,
                            input = event.input,
                            state = ToolState.Pending,
                        ),
                    )
                }
                is AgentEvent.ToolExecuted -> {
                    updateToolState(event.id, ToolState.Done(event.result))
                    if (!event.result.isError && event.name in VAULT_WRITE_TOOLS) {
                        Toaster.success("${event.name}: ${event.result.content.lineSequence().first().take(80)}")
                    } else if (event.result.isError && event.name in VAULT_WRITE_TOOLS) {
                        Toaster.error("${event.name} failed")
                    }
                }
                is AgentEvent.ToolRejected -> updateToolState(event.id, ToolState.Rejected(event.reason))
                is AgentEvent.TurnComplete -> {
                    _ui.value = _ui.value.copy(streaming = "", isStreaming = false)
                    flushPersist()
                }
                is AgentEvent.Error -> {
                    appendItem(ChatItem.SystemError(event.message))
                    Toaster.error(event.message.take(120))
                    _ui.value = _ui.value.copy(streaming = "", isStreaming = false)
                    flushPersist()
                }
            }
        }
    }

    private suspend fun flushPersist() {
        if (pendingPersist.isEmpty()) return
        val toFlush = pendingPersist.toList()
        pendingPersist.clear()
        for (message in toFlush) repo.appendMessage(conversationId, message)
    }

    private fun appendItem(item: ChatItem) {
        _ui.value = _ui.value.copy(items = _ui.value.items + item)
    }

    private fun updateToolState(toolUseId: String, state: ToolState) {
        _ui.value = _ui.value.copy(
            items = _ui.value.items.map { item ->
                if (item is ChatItem.ToolCall && item.id == toolUseId) item.copy(state = state) else item
            },
        )
    }
}

/**
 * Replay a canonical message list into the ChatItem display model. Tool calls
 * pair with their tool_results by id; orphaned tool_uses (because the previous
 * session was interrupted mid-execution) render as Rejected.
 */
private fun messagesToChatItems(messages: List<Message>): List<ChatItem> {
    val toolStateById = mutableMapOf<String, ToolState>()
    for (message in messages) {
        if (message.role != Role.USER) continue
        for (content in message.content) {
            if (content is MessageContent.ToolResult) {
                toolStateById[content.toolUseId] = ToolState.Done(
                    com.juni.app.domain.tools.ToolResult(content.content, content.isError),
                )
            }
        }
    }

    val items = mutableListOf<ChatItem>()
    for (message in messages) {
        when (message.role) {
            Role.USER -> {
                val text = message.content.filterIsInstance<MessageContent.Text>()
                    .joinToString("\n") { it.text }
                val images = message.content.filterIsInstance<MessageContent.Image>()
                    .map { Base64.decode(it.base64, Base64.DEFAULT) }
                if (text.isNotEmpty() || images.isNotEmpty()) {
                    items += ChatItem.UserMessage(text, images)
                }
                // Tool results render inside their matching ToolCall card; nothing to add here.
            }
            Role.ASSISTANT -> {
                for (content in message.content) {
                    when (content) {
                        is MessageContent.Text -> items += ChatItem.AssistantText(content.text)
                        is MessageContent.ToolUse -> {
                            val state = toolStateById[content.id]
                                ?: ToolState.Rejected("Session ended before this tool finished.")
                            items += ChatItem.ToolCall(content.id, content.name, content.input, state)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
    return items
}
