package org.babyfish.graphql.provider.server.dsl.association

import org.babyfish.graphql.provider.server.dsl.FilterDSL
import org.babyfish.graphql.provider.server.dsl.GraphQLProviderDSL
import org.babyfish.graphql.provider.server.dsl.redis.EntityPropRedisDSL
import org.babyfish.graphql.provider.server.meta.impl.EntityPropImpl
import org.babyfish.kimmer.Immutable

@GraphQLProviderDSL
class MappedCollectionDSL<T: Immutable> internal constructor(
    private val entityProp: EntityPropImpl
) {
    fun filter(block: FilterDSL<T>.() -> Unit) {
    }

    fun redis(block: EntityPropRedisDSL<T>.() -> Unit) {
        EntityPropRedisDSL<T>(entityProp.redis).block()
    }
}