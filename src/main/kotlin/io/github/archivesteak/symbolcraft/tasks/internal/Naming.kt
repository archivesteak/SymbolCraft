package io.github.archivesteak.symbolcraft.tasks.internal

import io.github.archivesteak.symbolcraft.converter.IconNameTransformer
import io.github.archivesteak.symbolcraft.converter.NameTransformerFactory
import io.github.archivesteak.symbolcraft.plugin.NamingConfig

/** Materialises the [IconNameTransformer] described by a [NamingConfig]. */
internal fun NamingConfig.toTransformer(): IconNameTransformer =
    if (transformer.isPresent) {
        transformer.get()
    } else {
        NameTransformerFactory.fromConvention(
            convention = namingConvention.get(),
            suffix = suffix.get(),
            prefix = prefix.get(),
            removePrefix = removePrefix.get(),
            removeSuffix = removeSuffix.get(),
        )
    }
