package com.kirkisham.motif.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One-shot undo for destructive actions (0.10, item 1). The caller applies the change locally at
 * once and posts an entry; the pill shows for [WINDOW_MS] or until the user touches anything else.
 * When it goes away [Entry.commit] runs (the real API call, if it was deferred); tapping Undo
 * cancels that and runs [Entry.undo] instead. Posting a new entry commits the one before it.
 * Everything runs on the app scope so leaving a screen does not lose the commit.
 */
class UndoManager(private val scope: CoroutineScope) {
    class Entry(
        val id: Long,
        val label: String,
        val undo: suspend () -> Unit,
        val commit: suspend () -> Unit,
    )

    private val _current = MutableStateFlow<Entry?>(null)
    val current: StateFlow<Entry?> = _current.asStateFlow()
    private var timer: Job? = null
    private var seq = 0L

    /** Shows "[label] · Undo". [commit] runs when the pill expires or is dismissed; [undo] when tapped. */
    fun post(label: String = "Removed", commit: suspend () -> Unit = {}, undo: suspend () -> Unit) {
        dismiss()
        val e = Entry(++seq, label, undo, commit)
        _current.value = e
        timer = scope.launch {
            delay(WINDOW_MS)
            if (_current.value === e) { _current.value = null; runSafely(e.commit) }
        }
    }

    /** Hides the pill now and lets the action stand. Safe to call when nothing is pending. */
    fun dismiss() {
        val e = _current.value ?: return
        timer?.cancel(); timer = null
        _current.value = null
        scope.launch { runSafely(e.commit) }
    }

    /** Reverses the pending action. */
    fun undo() {
        val e = _current.value ?: return
        timer?.cancel(); timer = null
        _current.value = null
        scope.launch { runSafely(e.undo) }
    }

    private suspend fun runSafely(block: suspend () -> Unit) {
        try { block() } catch (_: Exception) { /* the caller already showed its plain line */ }
    }

    companion object { const val WINDOW_MS = 3_000L }
}
