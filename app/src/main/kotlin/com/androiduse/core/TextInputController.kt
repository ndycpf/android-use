package com.androiduse.core

import com.androiduse.protocol.ImeActionRequest
import com.androiduse.protocol.TypeTextRequest

data class TextInputResult(
    val method: String,
    val submitted: Boolean = false,
)

/**
 * Abstraction for injecting text, clearing fields, and triggering IME actions.
 */
interface TextInputController {
    suspend fun typeText(request: TypeTextRequest): TextInputResult
    suspend fun clearText(): TextInputResult
    suspend fun performImeAction(request: ImeActionRequest): TextInputResult
}
