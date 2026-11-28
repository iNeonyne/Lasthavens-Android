package com.neonyne.lasthavenlauncher

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class DiagnosticsAction {
  READ,
  CLEAR,
}

internal sealed interface DiagnosticsState {
  // Each accepted request needs its own identity for the composition effect.
  class Loading(val action: DiagnosticsAction) : DiagnosticsState

  data class Loaded(val content: String) : DiagnosticsState

  data object Cleared : DiagnosticsState

  data class Failed(val action: DiagnosticsAction) : DiagnosticsState
}

internal class DiagnosticsLog(
  private val read: () -> String,
  private val clear: () -> Unit,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  constructor(
    context: Context,
  ) : this({ Diagnostics.read(context) }, { Diagnostics.clear(context) })

  suspend fun execute(action: DiagnosticsAction): DiagnosticsState =
    withContext(dispatcher) {
      try {
        when (action) {
          DiagnosticsAction.READ -> DiagnosticsState.Loaded(read())
          DiagnosticsAction.CLEAR -> {
            clear()
            DiagnosticsState.Cleared
          }
        }
      } catch (_: IOException) {
        DiagnosticsState.Failed(action)
      } catch (_: SecurityException) {
        DiagnosticsState.Failed(action)
      }
    }
}
