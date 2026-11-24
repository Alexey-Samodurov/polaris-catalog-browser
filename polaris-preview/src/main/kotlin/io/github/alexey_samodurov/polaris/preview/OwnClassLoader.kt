package io.github.alexey_samodurov.polaris.preview

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Makes this module's class loader the thread's context class loader while a coroutine runs.
 *
 * Iceberg and the AWS SDK create their HTTP clients, FileIO and readers by reflection through the
 * thread's context class loader. Inside the IDE that is the platform's, which cannot see the
 * plugin's jars, so `RESTCatalog` fails to build its HTTP client. Applied on every resumption, on
 * whatever thread the coroutine lands.
 */
internal object OwnClassLoader :
    AbstractCoroutineContextElement(Key),
    ThreadContextElement<ClassLoader?> {
    object Key : CoroutineContext.Key<OwnClassLoader>

    private val loader: ClassLoader = OwnClassLoader::class.java.classLoader

    override fun updateThreadContext(context: CoroutineContext): ClassLoader? {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = loader
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: ClassLoader?) {
        Thread.currentThread().contextClassLoader = oldState
    }
}
