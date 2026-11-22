package com.androiduse.core

/**
 * Pure text composition helper for Android EditText/AccessibilityNodeInfo text entry.
 *
 * Android's `AccessibilityNodeInfo.getText()` frequently returns the placeholder hint text
 * when a field is empty (`isShowingHintText == true`). Treating hint text as empty prevents
 * accidentally prepending placeholders (e.g., "Search mail" + "invoice").
 */
object TextComposition {
    fun compose(
        existingText: String?,
        isShowingHintText: Boolean,
        newText: String,
        clearFirst: Boolean,
    ): String {
        if (clearFirst || isShowingHintText || existingText.isNullOrEmpty()) {
            return newText
        }
        return existingText + newText
    }
}
