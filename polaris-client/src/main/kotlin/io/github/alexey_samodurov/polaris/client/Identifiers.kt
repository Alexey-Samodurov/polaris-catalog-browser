package io.github.alexey_samodurov.polaris.client

/** An Iceberg namespace: one or more levels, `a.b` being `["a", "b"]`. */
data class Namespace(val levels: List<String>) {
    init {
        require(levels.isNotEmpty()) { "namespace has no levels" }
    }

    val name: String get() = levels.last()

    override fun toString(): String = levels.joinToString(".")

    companion object {
        fun of(vararg levels: String): Namespace = Namespace(levels.toList())
    }
}

/** A table or a view inside a namespace. */
data class TableIdentifier(val namespace: Namespace, val name: String) {
    override fun toString(): String = "$namespace.$name"
}
