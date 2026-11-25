package org.tapetape.v2.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import org.tapetape.v2.Logx
import org.tapetape.v2.TapeTape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun <T> coreQuery(initial: T, vararg keys: Any?, block: () -> T): State<T> {
    val revision by TapeTape.revision.collectAsState()
    return produceState(initial, revision, *keys) {
        value = withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            runCatching(block).also {
                val ms = (System.nanoTime() - start) / 1_000_000
                if (ms > 200) Logx.w("slow core query ${ms}ms")
            }.getOrElse {
                Logx.w("query failed", it)
                value
            }
        }
    }
}
