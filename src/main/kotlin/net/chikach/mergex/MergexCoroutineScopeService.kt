package net.chikach.mergex

import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope

/**
 * Application-level service whose only job is to expose a [CoroutineScope] tied
 * to the application lifecycle.
 *
 * This is the public, supported way to obtain a coroutine scope from a plugin
 * (the platform injects it via the constructor), and it replaces the scope that
 * the now-internal `ApplicationStarterBase`/`ModernApplicationStarter` used to
 * provide for free.
 */
@Service(Service.Level.APP)
internal class MergexCoroutineScopeService(val scope: CoroutineScope)
