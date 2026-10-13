package com.example.counterproof

import androidx.lifecycle.ViewModel

/**
 * The proof target. Plain `var count` (mutable backing field + setter),
 * an increment method, an explicit setter, and a no-arg reporter —
 * everything Phases 1-2 need: read, write, call.
 */
class CounterViewModel : ViewModel() {
    var count: Int = 0

    fun increment() {
        count += 1
    }

    fun assignCount(v: Int) {
        count = v
    }

    fun load(): String = "count=$count"
}
