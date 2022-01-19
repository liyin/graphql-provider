package org.babyfish.graphql.provider.server.dsl

import io.r2dbc.spi.Statement
import org.babyfish.graphql.provider.server.dsl.redis.AbstractRedisDependencyDSL
import org.babyfish.graphql.provider.server.meta.impl.EntityPropImpl

class ComputedDSL<E, T> internal constructor(
    private val entityProp: EntityPropImpl
) {

    fun implementation(block: suspend ImplementationContext<E>.() -> T) {}

    fun batchImplementation(block: suspend BatchImplementationContext<E>.() -> Map<out Any, T>) {}

    fun redis(block: AbstractRedisDependencyDSL<E>.() -> Unit) {

    }
}

interface ImplementationContext<E> {
    val row: E
    fun createStatement(sql: String): Statement
}

interface BatchImplementationContext<E> {
    val rows: List<E>
    fun createStatement(sql: String): Statement
}