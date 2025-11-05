package com.example.geminichat.llm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.StringBuilder
import kotlin.math.max

/**
 * Handles LLM state, model downloading, initialization, and inference.
 */
class LlmViewModel(
    private val appContext: Context,
    initialModelPath: String,
    private var hfToken: String
) : ViewModel() {

    private val STATS = listOf(
        Stat(id = "time_to_first_token", label = "1st token", unit = "sec"),
        Stat(id = "prefill_speed", label = "Prefill speed", unit = "tokens/s"),
        Stat(id = "decode_speed", label = "Decode speed", unit = "tokens/s"),
        Stat(id = "latency", label = "Latency", unit = "sec"),
    )

    private val defaultAccelerator = "CPU"
    private val whitespaceRegex = Regex("\\s+")

    var latestBenchmarkResult by mutableStateOf<ChatMessageBenchmarkLlmResult?>(null)
        private set

    // --- Public State for Composable Functions (used by MainActivity) ---
    var inProgress by mutableStateOf(false)
        private set
    var preparing by mutableStateOf(false) // Model is being loaded/initialized
        private set
    var isDownloading by mutableStateOf(false)
        private set
    var downloadProgress by mutableFloatStateOf(0f)
        private set
    var downloadComplete by mutableStateOf(false)
        private set
    var needsInitialization by mutableStateOf(false)
        private set
    var isDownloadPaused by mutableStateOf(false)
        private set

    var response by mutableStateOf<String?>(null) // Final LLM description
        private set
    var error by mutableStateOf<String?>(null) // Error message
        private set
    var currentModelPath by mutableStateOf(initialModelPath) // Path to the currently used model
    var isModelReady by mutableStateOf(false) // Whether the model is fully initialized and ready
        private set

    // --- Internal Properties ---
    private val httpClient = OkHttpClient()
    private var currentDownloadCall: Call? = null
    private var lastDownloadUrl: String? = null
    private var lastFileName: String? = null
    private var lastToken: String? = null
    private var llmInstance: LlmModelInstance? = null // The actual LLM engine instance

    init {
        val initialFile = File(currentModelPath)
        if (isValidModelFile(initialFile)) {
            downloadComplete = true
            currentModelPath = initialFile.absolutePath
            initialize(currentModelPath)
        } else {
            currentModelPath = ""
        }

        val savedToken = appContext
            .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getString("huggingface_token", null)
        if (!savedToken.isNullOrBlank()) {
            hfToken = savedToken
        }

    }

    override fun onCleared() {
        LlmModelHelper.cleanUp(llmInstance)
        llmInstance = null
        super.onCleared()
    }

    /**
     * Initializes the LLM engine using the specified model path.
     */
    fun initialize(modelPath: String) {
        if (preparing) return
        preparing = true
        error = null
        currentModelPath = modelPath
        needsInitialization = false

        // Launch initialization on a background thread
        viewModelScope.launch(Dispatchers.Default) {
            val result = LlmModelHelper.initialize(
                context = appContext,
                modelPath = modelPath,
                enableVision = true,
            )
            result.onSuccess { inst ->
                llmInstance = inst
                withContext(Dispatchers.Main) {
                    error = null
                    isModelReady = true
                    downloadComplete = true
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    error = e.message ?: "Model initialization failed."
                    isModelReady = false
                    needsInitialization = true
                }
            }
            withContext(Dispatchers.Main) { preparing = false }
        }
    }

    /**
     * Delete the current model file
     */
    fun deleteModel() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(currentModelPath)
                if (file.exists()) {
                    // Clean up the current instance first
                    LlmModelHelper.cleanUp(llmInstance)
                    llmInstance = null

                    // Then delete the file
                    val deleted = file.delete()
                    withContext(Dispatchers.Main) {
                        if (deleted) {
                            currentModelPath = ""
                            downloadComplete = false
                            needsInitialization = false
                            isModelReady = false
                            downloadProgress = 0f
                            error = null
                            lastDownloadUrl = null
                            lastFileName = null
                        } else {
                            error = "Failed to delete model file"
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = "Error deleting model: ${e.message}"
                }
            }
        }
    }

    /**
     * Clears LLM response and error states.
     */
    fun clearState() {
        response = null
        error = null
    }

    /**
     * Updates the in-memory Hugging Face token for the current session.
     */
    fun updateToken(token: String) {
        hfToken = token.trim()
        appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("huggingface_token", hfToken)
            .apply()
    }

    /**
     * Attempts to stop the current LLM inference process immediately.
     * This is the core function for the 'Stop' button feature.
     */
    fun cancelInference() {
        llmInstance?.let { inst ->
            Log.d("LlmViewModel", "Stopping response for current model instance…")
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    inst.session.cancelGenerateResponseAsync()
                    Log.d("LlmViewModel", "Cancelled generation gracefully.")
                } catch (e: Exception) {
                    Log.w(
                        "LlmViewModel",
                        "Graceful cancel failed, resetting session as fallback",
                        e
                    )
                    try {
                        LlmModelHelper.resetSession(inst, enableVision = true)
                    } catch (_: Exception) {
                        // Ignore fallback errors, state update handles any lingering issues
                    }
                }

                withContext(Dispatchers.Main) {
                    error = "Response generation stopped by user."
                    inProgress = false
                }
            }
        } ?: run {
            error = "No active model session to cancel."
            inProgress = false
        }
    }

    fun downloadModel(
        downloadUrl: String,
        fileName: String,
        token: String,
        onComplete: ((String) -> Unit)? = null
    ) {
        if (isDownloading) return

        val sharedPrefs = appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val trimmedToken = token.trim()
        if (trimmedToken.isNotBlank()) {
            hfToken = trimmedToken
            sharedPrefs.edit().putString("huggingface_token", hfToken).apply()
        } else if (hfToken.isBlank()) {
            val savedToken = sharedPrefs.getString("huggingface_token", "") ?: ""
            if (savedToken.isNotBlank()) {
                hfToken = savedToken
            }
        }

        if (hfToken.isBlank()) {
            error = "Please enter a valid Hugging Face token to start the download."
            return
        }

        val llmDir = File(appContext.filesDir, "llm").apply { mkdirs() }
        val tmpFile = File(llmDir, "$fileName.part")
        val finalFile = File(llmDir, fileName)

        lastDownloadUrl = downloadUrl
        lastFileName = fileName
        lastToken = hfToken
        isDownloading = true
        if (!tmpFile.exists() || tmpFile.length() == 0L) {
            downloadProgress = 0f
        }
        downloadComplete = false
        needsInitialization = false
        isModelReady = false
        error = null
        isDownloadPaused = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                var downloadedBytes = tmpFile.length()
                val requestBuilder = Request.Builder()
                    .url(downloadUrl)
                    .header("Authorization", "Bearer $hfToken")

                if (downloadedBytes > 0) {
                    Log.d("LlmViewModel", "Resuming download from byte $downloadedBytes")
                    requestBuilder.header("Range", "bytes=$downloadedBytes-")
                }

                val call = httpClient.newCall(requestBuilder.build())
                currentDownloadCall = call

                call.execute().use { response ->
                    if (response.code == 416) {
                        if (finalFile.exists()) {
                            finalFile.delete()
                        }
                        if (!tmpFile.renameTo(finalFile)) {
                            throw IOException("Failed to finalize resumed download")
                        }
                        if (!isValidModelFile(finalFile)) {
                            finalFile.delete()
                            throw IOException("Downloaded file is smaller than expected size")
                        }
                        withContext(Dispatchers.Main) {
                            completeDownload(finalFile)
                            onComplete?.invoke(finalFile.absolutePath)
                        }
                        return@launch
                    }

                    if (!response.isSuccessful) {
                        val msg = if (response.code == 401) {
                            "401 Unauthorized – invalid token"
                        } else {
                            "HTTP ${response.code}: ${response.message}"
                        }
                        throw IOException("Download failed: $msg")
                    }

                    val body = response.body ?: throw IOException("Empty response body")
                    val reportedLength = body.contentLength()
                    val totalBytes = if (reportedLength > 0) reportedLength + downloadedBytes else -1L

                    if (totalBytes > 0 && downloadedBytes > 0) {
                        withContext(Dispatchers.Main) {
                            val progress = downloadedBytes.toFloat() / totalBytes.toFloat()
                            downloadProgress = progress.coerceIn(0f, 1f)
                        }
                    }

                    val buffer = ByteArray(8_192)
                    var bytesRead: Int
                    body.byteStream().use { input ->
                        FileOutputStream(tmpFile, true).use { output ->
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                if (totalBytes > 0) {
                                    val progress = downloadedBytes.toFloat() / totalBytes.toFloat()
                                    withContext(Dispatchers.Main) {
                                        downloadProgress = progress.coerceIn(0f, 1f)
                                    }
                                }
                            }
                        }
                    }
                }

                if (finalFile.exists() && finalFile != tmpFile && !finalFile.delete()) {
                    throw IOException("Unable to replace existing model file")
                }
                if (!tmpFile.renameTo(finalFile)) {
                    throw IOException("Failed to move downloaded file into place")
                }

                if (!isValidModelFile(finalFile)) {
                    finalFile.delete()
                    throw IOException("Downloaded file is smaller than expected size")
                }

                withContext(Dispatchers.Main) {
                    completeDownload(finalFile)
                    onComplete?.invoke(finalFile.absolutePath)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    when {
                        isDownloadPaused -> {
                            error = "Download paused."
                        }
                        currentDownloadCall?.isCanceled() == true -> {
                            error = "Download cancelled."
                            lastDownloadUrl = null
                            lastFileName = null
                            lastToken = null
                        }
                        else -> {
                            isDownloadPaused = true
                            error = e.message ?: "Download interrupted. Tap Resume to continue."
                        }
                    }
                    downloadComplete = false
                    Log.e("LlmViewModel", "Download failed", e)
                }
            } finally {
                currentDownloadCall = null
                withContext(Dispatchers.Main) {
                    isDownloading = false
                }
            }
        }
    }

    fun cancelDownload() {
        if (!isDownloading && !isDownloadPaused) return
        currentDownloadCall?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            lastFileName?.let { name ->
                val tmpFile = File(File(appContext.filesDir, "llm"), "$name.part")
                if (tmpFile.exists()) {
                    tmpFile.delete()
                }
            }
            withContext(Dispatchers.Main) {
                downloadProgress = 0f
                downloadComplete = false
                needsInitialization = false
                isDownloading = false
                isModelReady = false
                error = "Download cancelled."
                isDownloadPaused = false
                lastDownloadUrl = null
                lastFileName = null
                lastToken = null
            }
        }
    }

    fun pauseDownload() {
        if (!isDownloading || isDownloadPaused) return
        isDownloadPaused = true
        error = "Download paused."
        currentDownloadCall?.cancel()
    }

    fun resumeDownload() {
        if (!isDownloadPaused) return
        val url = lastDownloadUrl ?: return
        val fileName = lastFileName ?: return
        val token = lastToken ?: hfToken
        isDownloadPaused = false
        error = null
        downloadModel(url, fileName, token)
    }

    private fun completeDownload(file: File) {
        downloadComplete = true
        needsInitialization = true
        currentModelPath = file.absolutePath
        downloadProgress = 1f
        error = null
        isDownloadPaused = false
        lastDownloadUrl = null
        lastFileName = null
        lastToken = null
        Log.d("LlmViewModel", "Download complete: ${file.absolutePath}")

        initializeModel()
    }

    fun initializeModel() {
        if (preparing) return
        if (currentModelPath.isBlank()) {
            error = "Model path missing for initialization."
            return
        }
        needsInitialization = false
        initialize(currentModelPath)
    }

    private fun isValidModelFile(file: File?): Boolean {
        if (file == null) return false
        return file.exists() && file.length() >= MIN_MODEL_FILE_BYTES
    }

    /**
     * Runs the cropped bitmap through the multimodal LLM to generate a description.
     */
    fun describeImage(bitmap: Bitmap, userPrompt: String?, onResponseComplete: ((String) -> Unit)? = null) {
        val inst = llmInstance
        if (inst == null) {
            error = "AI Model is not ready. Please wait for initialization or download."
            Log.e("LlmViewModel", "describeImage: llmInstance is null.")
            return
        }

        // Use user-provided prompt if available, else fallback to default
        val prompt = if (!userPrompt.isNullOrBlank()) {
            userPrompt.trim()
        } else {
            "Describe the image."
        }

        viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                inProgress = true
                response = null
                error = null
                Log.d(
                    "LlmViewModel",
                    "describeImage: Starting image description with prompt: $prompt"
                )
            }
            try {
                LlmModelHelper.resetSession(inst, enableVision = true)
                val imgs = listOf(bitmap)

                val prefillTokens = max(estimateTokenCount(prompt), 1)
                var firstRun = true
                var timeToFirstToken = 0f
                var firstTokenTs = 0L
                var decodeTokens = 0
                var prefillSpeed = 0f
                var decodeSpeed = 0f
                val start = System.currentTimeMillis()
                val modelName = File(currentModelPath).name.ifBlank { currentModelPath.ifBlank { "unknown" } }

                latestBenchmarkResult = null

                // Run inference and stream the result back to the UI
                LlmModelHelper.runInference(
                    instance = inst,
                    input = prompt,
                    images = imgs,
                    resultListener = { partial, done ->
                        val curTs = System.currentTimeMillis()
                        var benchmarkResult: ChatMessageBenchmarkLlmResult? = null

                        if (partial.isNotEmpty()) {
                            if (firstRun) {
                                firstTokenTs = curTs
                                timeToFirstToken = (firstTokenTs - start).coerceAtLeast(0L).toFloat() / 1000f
                                if (timeToFirstToken > 0f) {
                                    prefillSpeed = prefillTokens.toFloat() / timeToFirstToken
                                    if (prefillSpeed.isNaN() || prefillSpeed.isInfinite()) {
                                        prefillSpeed = 0f
                                    }
                                }
                                firstRun = false
                            } else {
                                decodeTokens += max(estimateTokenCount(partial), 1)
                            }
                        }

                        if (done) {
                            val decodeDurationSec = if (firstTokenTs == 0L) 0f else (curTs - firstTokenTs).toFloat() / 1000f
                            decodeSpeed = if (decodeDurationSec > 0f) decodeTokens / decodeDurationSec else 0f
                            if (decodeSpeed.isNaN() || decodeSpeed.isInfinite()) {
                                decodeSpeed = 0f
                            }
                            val latencySeconds = (curTs - start).toFloat() / 1000f
                            benchmarkResult = ChatMessageBenchmarkLlmResult(
                                orderedStats = STATS,
                                statValues = mutableMapOf(
                                    "prefill_speed" to prefillSpeed,
                                    "decode_speed" to decodeSpeed,
                                    "time_to_first_token" to timeToFirstToken,
                                    "latency" to latencySeconds,
                                ),
                                running = false,
                                latencyMs = -1f,
                                accelerator = defaultAccelerator,
                            )
                        }

                        viewModelScope.launch(Dispatchers.Main) {
                            response = (response ?: "") + partial // Append the streamed response
                            if (done) {
                                benchmarkResult?.let {
                                    updateLastTextMessageLlmBenchmarkResult(
                                        model = modelName,
                                        llmBenchmarkResult = it,
                                        accelerator = defaultAccelerator,
                                    )
                                }
                                inProgress = false
                                // Call the callback with the final response
                                response?.let { onResponseComplete?.invoke(it) }
                                Log.d(
                                    "LlmViewModel",
                                    "describeImage: completed. Response: ${'$'}{response}"
                                )
                            }
                        }
                    },
                    cleanUpListener = {
                        // Cleanup logic is handled by the 'done' check in resultListener
                    }
                )
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = e.message ?: "Inference error"
                    inProgress = false
                    Log.e("LlmViewModel", "describeImage: Inference error: ${'$'}{e.message}")
                }
            }
        }
    }

    /**
     * Runs text inference for chat responses.
     */
    fun respondToText(input: String, conversationHistory: List<String> = emptyList(), onResponseComplete: ((String) -> Unit)? = null) {
        val inst = llmInstance
        if (inst == null) {
            error = "AI Model is not ready. Please wait for initialization or download."
            Log.e("LlmViewModel", "respondToText: llmInstance is null.")
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                inProgress = true
                response = null
                error = null
                Log.d("LlmViewModel", "respondToText: Starting text response. Input: $input")
            }
            try {
                LlmModelHelper.resetSession(inst, enableVision = false)

                // Build context from conversation history
                val contextBuilder = StringBuilder()
                if (conversationHistory.isNotEmpty()) {
                    contextBuilder.append("Previous conversation:\n")
                    conversationHistory.forEach { message ->
                        contextBuilder.append("$message\n")
                    }
                    contextBuilder.append("\nNow respond to: ")
                }

                // Add current input
                val contextualInput = if (conversationHistory.isNotEmpty()) {
                    "${contextBuilder}$input"
                } else {
                    input
                }

                val prefillTokens = max(estimateTokenCount(contextualInput), 1)
                var firstRun = true
                var timeToFirstToken = 0f
                var firstTokenTs = 0L
                var decodeTokens = 0
                var prefillSpeed = 0f
                var decodeSpeed = 0f
                val start = System.currentTimeMillis()
                val modelName = File(currentModelPath).name.ifBlank { currentModelPath.ifBlank { "unknown" } }

                latestBenchmarkResult = null

                // Run inference and stream the result back to the UI
                LlmModelHelper.runInference(
                    instance = inst,
                    input = contextualInput,
                    images = emptyList(),
                    resultListener = { partial, done ->
                        val curTs = System.currentTimeMillis()
                        var benchmarkResult: ChatMessageBenchmarkLlmResult? = null

                        if (partial.isNotEmpty()) {
                            if (firstRun) {
                                firstTokenTs = curTs
                                timeToFirstToken = (firstTokenTs - start).coerceAtLeast(0L).toFloat() / 1000f
                                if (timeToFirstToken > 0f) {
                                    prefillSpeed = prefillTokens.toFloat() / timeToFirstToken
                                    if (prefillSpeed.isNaN() || prefillSpeed.isInfinite()) {
                                        prefillSpeed = 0f
                                    }
                                }
                                firstRun = false
                            } else {
                                decodeTokens += max(estimateTokenCount(partial), 1)
                            }
                        }

                        if (done) {
                            val decodeDurationSec = if (firstTokenTs == 0L) 0f else (curTs - firstTokenTs).toFloat() / 1000f
                            decodeSpeed = if (decodeDurationSec > 0f) decodeTokens / decodeDurationSec else 0f
                            if (decodeSpeed.isNaN() || decodeSpeed.isInfinite()) {
                                decodeSpeed = 0f
                            }
                            val latencySeconds = (curTs - start).toFloat() / 1000f
                            benchmarkResult = ChatMessageBenchmarkLlmResult(
                                orderedStats = STATS,
                                statValues = mutableMapOf(
                                    "prefill_speed" to prefillSpeed,
                                    "decode_speed" to decodeSpeed,
                                    "time_to_first_token" to timeToFirstToken,
                                    "latency" to latencySeconds,
                                ),
                                running = false,
                                latencyMs = -1f,
                                accelerator = defaultAccelerator,
                            )
                        }

                        viewModelScope.launch(Dispatchers.Main) {
                            response = (response ?: "") + partial // Append the streamed response
                            if (done) {
                                benchmarkResult?.let {
                                    updateLastTextMessageLlmBenchmarkResult(
                                        model = modelName,
                                        llmBenchmarkResult = it,
                                        accelerator = defaultAccelerator,
                                    )
                                }
                                inProgress = false
                                // Call the callback with the final response
                                response?.let { onResponseComplete?.invoke(it) }
                                Log.d(
                                    "LlmViewModel",
                                    "respondToText: Text response completed. Response: ${'$'}{response}"
                                )
                            }
                        }
                    },
                    cleanUpListener = {
                        // Cleanup logic is handled by the 'done' check in resultListener
                    }
                )
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = e.message ?: "Inference error"
                    inProgress = false
                    Log.e("LlmViewModel", "respondToText: Inference error: ${'$'}{e.message}")
                }
            }
        }
    }

    fun consumeLatestBenchmarkResult(): ChatMessageBenchmarkLlmResult? {
        val result = latestBenchmarkResult
        latestBenchmarkResult = null
        return result
    }

    @Suppress("UNUSED_PARAMETER")
    private fun updateLastTextMessageLlmBenchmarkResult(
        model: String,
        llmBenchmarkResult: ChatMessageBenchmarkLlmResult,
        accelerator: String?,
    ) {
        latestBenchmarkResult = llmBenchmarkResult
    }

    private fun estimateTokenCount(text: String): Int {
        if (text.isBlank()) return 0
        return text.trim().split(whitespaceRegex).count { it.isNotBlank() }
    }

    /**
     * Resets the response/error states. Called when moving from Result back to Chat screen,
     * or after a message has been added to history (for cleanup).
     */
    fun clearResponse() {
        response = null
        // Note: We don't clear 'error' here because the ChatScreen needs it immediately
        // after cancellation to insert the "stopped by user" message into the chat history.
        inProgress = false
    }


    companion object {
        private const val MIN_MODEL_FILE_BYTES = 1_000_000_000L

        fun provideFactory(appContext: Context, modelPath: String, token: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    // Passed from MainActivity:
                    return LlmViewModel(appContext.applicationContext, modelPath, token) as T
                }
            }
    }
}
