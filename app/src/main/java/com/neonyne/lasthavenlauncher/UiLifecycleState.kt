package com.neonyne.lasthavenlauncher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/** True only while the host Activity is started and its UI can be shown to the user. */
@Composable
internal fun rememberLifecycleStarted(lifecycleOwner: LifecycleOwner?): Boolean {
  val lifecycle = lifecycleOwner?.lifecycle
  var started by
    remember(lifecycle) {
      mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true)
    }

  DisposableEffect(lifecycle) {
    if (lifecycle == null) {
      return@DisposableEffect onDispose {}
    }

    val observer = LifecycleEventObserver { owner, _ ->
      started = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
    lifecycle.addObserver(observer)
    started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    onDispose { lifecycle.removeObserver(observer) }
  }

  return started
}
