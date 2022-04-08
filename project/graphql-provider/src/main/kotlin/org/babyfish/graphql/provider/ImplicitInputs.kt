package org.babyfish.graphql.provider

import org.babyfish.kimmer.sql.Entity
import org.babyfish.kimmer.sql.SaveOptionsDSL
import java.lang.UnsupportedOperationException

class ImplicitInputs<E: Entity<*>, M: InputMapper<E, *>>(
    val entities: List<E>,
    val saveOptionsBlock: SaveOptionsDSL<E>.() -> Unit
) {
    @Suppress("UNUSED")
    private fun suppressWaringOfUnusedGenericArgumentM(): M =
        throw UnsupportedOperationException()
}