package org.babyfish.graphql.provider.dsl.graphql

import org.babyfish.graphql.provider.dsl.GraphQLProviderDSL

@GraphQLProviderDSL
class EntityPropGraphQLDSL internal constructor() {
    var hidden: Boolean = false
    var batchSize: Int? = null
}