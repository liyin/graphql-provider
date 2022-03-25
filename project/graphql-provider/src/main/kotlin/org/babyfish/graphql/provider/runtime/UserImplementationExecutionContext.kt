package org.babyfish.graphql.provider.runtime

import graphql.schema.DataFetchingEnvironment
import org.babyfish.graphql.provider.meta.ModelProp
import java.util.concurrent.CompletableFuture

class UserImplementationExecutionContext(
    val prop: ModelProp,
    val env: DataFetchingEnvironment,
    val argumentsConverter: ArgumentsConverter
) {
    internal var result: CompletableFuture<Any?>? = null
}

internal fun <R> withUserImplementationExecutionContext(
    ctx: UserImplementationExecutionContext,
    block: () -> R
): R {
    val oldContext = userImplementationExecutionContextLocal.get()
    userImplementationExecutionContextLocal.set(ctx)
    return try {
        block()
    } finally {
        if (oldContext !== null) {
            userImplementationExecutionContextLocal.set(oldContext)
        } else {
            userImplementationExecutionContextLocal.remove()
        }
    }
}

internal val userImplementationExecutionContext: UserImplementationExecutionContext
    get() = userImplementationExecutionContextLocal.get() ?: error(
        "No FilterExecutionContext. wrapper functions of " +
            "EntityMapper.Runtime.implementation and EntityMapper.Runtime.batchImplementation " +
            "cannot be invoked directly because they can only be invoked by the framework internally"
    )

private val userImplementationExecutionContextLocal =
    ThreadLocal<UserImplementationExecutionContext>()