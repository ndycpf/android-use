package com.androiduse.core

import com.androiduse.protocol.ErrorCode
import com.androiduse.protocol.ImageFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decorator around a [ScreenCapturer] that:
 * 1. Serializes concurrent capture requests so parallel callers don't trigger OS throttling.
 * 2. Serves a short-TTL cached frame when [CaptureOptions.allowCachedMs] permits and options match.
 * 3. Retries bounded times on [ErrorCode.SCREENSHOT_RATE_LIMITED] before propagating 429.
 */
class ResilientScreenCapturer(
    private val delegate: ScreenCapturer,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : ScreenCapturer {

    private val captureMutex = Mutex()
    private var lastCacheKey: CacheKey? = null
    private var lastResult: CaptureResult? = null

    override suspend fun capture(options: CaptureOptions): CaptureResult {
        options.validate()
        val key = CacheKey(
            format = options.format,
            quality = options.quality,
            maxDimension = options.maxDimension,
        )

        return captureMutex.withLock {
            if (options.allowCachedMs > 0L) {
                val cached = lastResult
                if (cached != null && lastCacheKey == key) {
                    val ageMs = clockMs() - cached.timestampMs
                    if (ageMs in 0..options.allowCachedMs) {
                        return@withLock cached
                    }
                }
            }

            var lastRateLimitError: AndroidUseException? = null
            for (attempt in 0..maxRetries) {
                try {
                    val result = delegate.capture(options)
                    lastCacheKey = key
                    lastResult = result
                    return@withLock result
                } catch (e: AndroidUseException) {
                    if (e.code == ErrorCode.SCREENSHOT_RATE_LIMITED && attempt < maxRetries) {
                        lastRateLimitError = e
                        delay(retryDelayMs * (attempt + 1))
                    } else {
                        throw e
                    }
                }
            }
            throw lastRateLimitError ?: AndroidUseException.screenshotRateLimited()
        }
    }

    private data class CacheKey(
        val format: ImageFormat,
        val quality: Int,
        val maxDimension: Int?,
    )

    companion object {
        const val DEFAULT_MAX_RETRIES: Int = 2
        const val DEFAULT_RETRY_DELAY_MS: Long = 350L
    }
}
