package io.github.alexey_samodurov.polaris

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/** The coroutine scope the platform gives this plugin per project; cancelled when the project closes. */
@Service(Service.Level.PROJECT)
class PolarisProjectScope(@Suppress("unused") private val project: Project, val scope: CoroutineScope)
