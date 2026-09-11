package io.github.archivesteak.example

/** Stand-in for real shared logic; exported to iOS through the `Shared` framework. */
class Greeting {
    fun greet(): String = "SymbolCraft symbols from :shared"
}
