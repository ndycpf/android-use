package com.androiduse.android

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.androiduse.core.AccessibilityServiceRegistry
import com.androiduse.core.AndroidUseException
import com.androiduse.core.TextComposition
import com.androiduse.core.TextInputController
import com.androiduse.core.TextInputResult
import com.androiduse.protocol.ImeActionRequest
import com.androiduse.protocol.TypeTextRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * [TextInputController] backed by [AccessibilityNodeInfo.ACTION_SET_TEXT],
 * clipboard + [AccessibilityNodeInfo.ACTION_PASTE] fallback for WebViews/custom editors,
 * and [AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER] for submission.
 */
class AccessibilityTextInputController(
    private val context: Context,
    private val accessibilityRegistry: AccessibilityServiceRegistry,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : TextInputController {

    override suspend fun typeText(request: TypeTextRequest): TextInputResult {
        val service = requireService()
        val target = findTargetInputNode(service)
            ?: throw AndroidUseException.noFocusedInput()

        val composed = TextComposition.compose(
            existingText = target.text?.toString(),
            isShowingHintText = target.isShowingHintText,
            newText = request.text,
            clearFirst = request.clearFirst,
        )

        val setTextArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, composed)
        }
        val method = if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)) {
            METHOD_SET_TEXT
        } else if (request.useClipboardFallback) {
            pasteViaClipboard(target = target, text = request.text, clearFirst = request.clearFirst)
            METHOD_CLIPBOARD_PASTE
        } else {
            throw AndroidUseException.actionFailed(
                message = "ACTION_SET_TEXT returned false on focused input.",
                retryable = true,
            )
        }

        val submitted = if (request.submit) {
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else {
            false
        }

        return TextInputResult(method = method, submitted = submitted)
    }

    override suspend fun clearText(): TextInputResult {
        val service = requireService()
        val target = findTargetInputNode(service)
            ?: throw AndroidUseException.noFocusedInput()

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            throw AndroidUseException.actionFailed(
                message = "Failed to clear text on focused input.",
                retryable = true,
            )
        }
        return TextInputResult(method = METHOD_SET_TEXT, submitted = false)
    }

    override suspend fun performImeAction(request: ImeActionRequest): TextInputResult {
        val normalized = request.action.trim().lowercase()
        if (normalized != ACTION_ENTER) {
            throw AndroidUseException.invalidRequest(
                "Unsupported IME action '${request.action}'. Supported actions: 'enter'.",
            )
        }

        val service = requireService()
        val target = findTargetInputNode(service)
            ?: throw AndroidUseException.noFocusedInput()

        val ok = target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        if (!ok) {
            throw AndroidUseException.actionFailed(
                message = "ACTION_IME_ENTER returned false on focused input.",
                retryable = true,
            )
        }
        return TextInputResult(method = METHOD_IME_ENTER, submitted = true)
    }

    private suspend fun pasteViaClipboard(
        target: AccessibilityNodeInfo,
        text: String,
        clearFirst: Boolean,
    ) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: throw AndroidUseException.actionFailed("ClipboardManager is unavailable.")

        val previousClip: ClipData? = withContext(mainDispatcher) {
            runCatching { clipboard.primaryClip }.getOrNull()
        }

        try {
            if (clearFirst) {
                val clearArgs = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)) {
                    val existingLength = if (target.isShowingHintText) 0 else target.text?.length ?: 0
                    if (existingLength > 0) {
                        val selectAllArgs = Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existingLength)
                        }
                        target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs)
                    }
                }
            }

            withContext(mainDispatcher) {
                clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            }

            val pasted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (!pasted) {
                throw AndroidUseException.actionFailed(
                    message = "Both ACTION_SET_TEXT and ACTION_PASTE failed on focused input.",
                    retryable = true,
                )
            }
        } finally {
            if (previousClip != null) {
                delay(CLIPBOARD_RESTORE_DELAY_MS)
                withContext(mainDispatcher) {
                    runCatching { clipboard.setPrimaryClip(previousClip) }
                }
            }
        }
    }

    private fun findTargetInputNode(service: AccessibilityService): AccessibilityNodeInfo? {
        service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }

        val root = service.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var fallbackFocused: AccessibilityNodeInfo? = null
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_SEARCH_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node.isFocused && node.isEditable) {
                return node
            }
            if (fallbackFocused == null && node.isFocused) {
                fallbackFocused = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return fallbackFocused
    }

    private fun requireService(): AccessibilityService =
        accessibilityRegistry.currentService()
            ?: throw AndroidUseException.accessibilityNotConnected()

    companion object {
        const val METHOD_SET_TEXT: String = "set_text"
        const val METHOD_CLIPBOARD_PASTE: String = "clipboard_paste"
        const val METHOD_IME_ENTER: String = "ime_enter"
        private const val ACTION_ENTER: String = "enter"
        private const val CLIP_LABEL: String = "android-use"
        private const val CLIPBOARD_RESTORE_DELAY_MS: Long = 150L
        private const val MAX_SEARCH_NODES: Int = 1_500
    }
}
