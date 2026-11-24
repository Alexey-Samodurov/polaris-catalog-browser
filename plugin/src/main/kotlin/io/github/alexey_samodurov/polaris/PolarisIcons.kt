package io.github.alexey_samodurov.polaris

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object PolarisIcons {
    /** The plugin's own mark (a star on a grid), monochrome, for the tool window and connection nodes. */
    @JvmField
    val Polaris: Icon = IconLoader.getIcon("/icons/polaris.svg", PolarisIcons::class.java)
}
