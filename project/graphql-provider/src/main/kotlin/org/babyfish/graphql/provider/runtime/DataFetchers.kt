package org.babyfish.graphql.provider.runtime

import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.mono
import org.babyfish.graphql.provider.meta.*
import org.babyfish.graphql.provider.meta.impl.MutationPropImpl
import org.babyfish.graphql.provider.runtime.cfg.GraphQLProviderProperties
import org.babyfish.graphql.provider.runtime.loader.BatchLoaderByParentId
import org.babyfish.graphql.provider.runtime.loader.ManyToManyBatchLoader
import org.babyfish.graphql.provider.runtime.loader.NonManyToManyBatchLoader
import org.babyfish.kimmer.Draft
import org.babyfish.kimmer.Immutable
import org.babyfish.kimmer.graphql.*
import org.babyfish.kimmer.produce
import org.babyfish.kimmer.sql.Entity
import org.babyfish.kimmer.sql.ast.Expression
import org.babyfish.kimmer.sql.ast.count
import org.babyfish.kimmer.sql.ast.eq
import org.babyfish.kimmer.sql.ast.query.MutableRootQuery
import org.babyfish.kimmer.sql.ast.value
import org.babyfish.kimmer.sql.meta.config.Column
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderOptions
import org.springframework.context.ApplicationContext
import java.util.concurrent.CompletableFuture
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.javaMethod

open class DataFetchers(
    private val r2dbcClient: R2dbcClient,
    private val argumentsConverter: ArgumentsConverter,
    private val applicationContext: ApplicationContext,
    private val cfg: GraphQLProviderProperties
) {

    @Suppress("UNCHECKED_CAST")
    fun fetch(prop: QueryProp, env: DataFetchingEnvironment): CompletableFuture<Any?> =
        mono(Dispatchers.Unconfined) {
            if (prop.isConnection) {
                fetchConnectionAsync(prop, env)
            } else {
                r2dbcClient.execute {
                    val query =
                        r2dbcClient.sqlClient.createQuery(prop.targetType!!.kotlinType as KClass<Entity<FakeID>>) {
                            prop.filter.execute(
                                FilterExecutionContext(prop, env, argumentsConverter, this)
                            )
                            select(table)
                        }
                    if (prop.isList) {
                        query.execute(it)
                    } else {
                        query.execute(it).firstOrNull()
                    }
                }
            }
        }.toFuture()

    fun fetch(prop: ModelProp, env: DataFetchingEnvironment): CompletableFuture<Any?> =
        fetchUserImplementation(prop, env) ?:
            fetchSystemImplementation(prop, env)

    private fun fetchUserImplementation(prop: ModelProp, env: DataFetchingEnvironment): CompletableFuture<Any?>? {
        val userImplementation = prop.userImplementation ?: return null
        return userImplementation.execute(
            UserImplementationExecutionContext(prop, env, argumentsConverter)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun fetchSystemImplementation(prop: ModelProp, env: DataFetchingEnvironment): CompletableFuture<Any?> {
        if (prop.isConnection) {
            return mono(Dispatchers.Unconfined) {
                fetchConnectionAsync(prop, env)
            }.toFuture() as CompletableFuture<Any?>
        }
        val entity = env.getSource<Entity<*>>()
        if (prop.isReference && prop.storage is Column && Immutable.isLoaded(entity, prop.immutableProp)) {
            val parent = Immutable.get(entity, prop.immutableProp) as Entity<*>?
            if (parent === null) {
                return CompletableFuture.completedFuture(null)
            }
            val parentId = Immutable.get(parent, prop.targetType!!.idProp.immutableProp)
            if (env.arguments.isEmpty()) {
                val fields = env.selectionSet.fields
                if (fields.size == 1 && fields[0].name == "id") {
                    return CompletableFuture.completedFuture(
                            produce(prop.targetType!!.kotlinType) {
                            Draft.set(this, prop.targetType!!.idProp.immutableProp, parentId)
                        }
                    )
                }
            }
            return env.loaderByParentId(prop).load(parentId)
        } else {
            val future = env.loaderById(prop).load(entity.id)
            if (prop.isReference) {
                return future.thenApply { it.firstOrNull() }
            }
            return future.thenApply { it ?: emptyList<Any>() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun fetchConnectionAsync(
        prop: GraphQLProp,
        env: DataFetchingEnvironment
    ): Connection<*> =
        r2dbcClient.execute {
            val query = r2dbcClient.sqlClient.createQuery(prop.targetType!!.kotlinType as KClass<Entity<FakeID>>) {
                if (prop is ModelProp) {
                    val sourceId = env.getSource<Entity<*>>().id
                    val sourceTable = table
                        .`←joinConnection`(
                            prop.kotlinProp as KProperty1<Entity<FakeID>, Connection<Entity<FakeID>>>
                        )
                    where { sourceTable.id eq value(sourceId) as Expression<FakeID> }
                }
                val filter = when (prop) {
                    is QueryProp -> prop.filter
                    is ModelProp -> prop.filter
                    else -> error("Internal bug: DataFetchers can only accept QueryProp and ModelProp")
                }
                filter?.execute(
                    FilterExecutionContext(prop, env, argumentsConverter, this)
                )
                select(table)
            }
            val nodeType = prop.targetType!!.kotlinType as KClass<Entity<FakeID>>
            val countOnce = AsyncOnce {
                query
                    .reselect {
                        select(table.id.count())
                    }.withoutSortingAndPaging()
                    .execute(it)
                    .first()
                    .toInt()
            }
            val (limit, offset) = env.limit(countOnce)
            val nodes = if (limit > 0) {
                query.limit(limit, offset).execute(it)
            } else {
                emptyList()
            }
            produceConnectionAsync(nodeType) {
                totalCount = countOnce.get()
                edges = nodes.mapIndexed { index, node ->
                    produceEdgeDraftAsync(nodeType) {
                        this.node = node
                        cursor = indexToCursor(offset + index)
                    }
                }
                pageInfo().apply {
                    hasPreviousPage = offset > 0
                    hasNextPage = offset + limit < countOnce.get()
                    startCursor = indexToCursor(offset)
                    endCursor = indexToCursor(offset + limit - 1)
                }
            }
        }

    private fun DataFetchingEnvironment.loaderByParentId(prop: ModelProp): DataLoader<Any, Any?> {
        val dataLoaderKey = "graphql-provider:loader-by-parent-id:${prop}"
        return dataLoaderRegistry.computeIfAbsent(dataLoaderKey) {
            DataLoaderFactory.newMappedDataLoader(
                BatchLoaderByParentId(r2dbcClient, prop) {
                    applyFilter(prop, it)
                },
                DataLoaderOptions().setMaxBatchSize(cfg.batchSize(prop))
            )
        }
    }

    private fun DataFetchingEnvironment.loaderById(prop: ModelProp): DataLoader<Any, List<Any>> {
        val dataLoaderKey = "graphql-provider:loader-by-id:${prop}"
        return dataLoaderRegistry.computeIfAbsent(dataLoaderKey) {
            DataLoaderFactory.newMappedDataLoader(
                when {
                    prop.isReference || prop.opposite?.isReference == true ->
                        NonManyToManyBatchLoader(r2dbcClient, prop) {
                            applyFilter(prop, it)
                        }
                    else ->
                        ManyToManyBatchLoader(r2dbcClient, prop) {
                            applyFilter(prop, it)
                        }
                },
                DataLoaderOptions().setMaxBatchSize(cfg.batchSize(prop))
            )
        }
    }

    private fun DataFetchingEnvironment.applyFilter(prop: GraphQLProp, query: MutableRootQuery<Entity<FakeID>, FakeID>) {
        val filter = when (prop) {
            is QueryProp -> prop.filter
            is ModelProp -> prop.filter
            else -> null
        }
        filter?.let {
            it.execute(FilterExecutionContext(prop, this, argumentsConverter, query))
        }
    }

    fun fetch(prop: MutationProp, env: DataFetchingEnvironment): CompletableFuture<Any?> {
        val function = (prop as MutationPropImpl).function
        val javaMethod = function.javaMethod ?: error("Internal bug: No java method for '$function'")
        val owner = applicationContext.getBean(javaMethod.declaringClass)
        val args = argumentsConverter.convert(
            prop.arguments,
            owner,
            env
        )
        return mono(Dispatchers.Unconfined) {
            function.callSuspend(*args)
        }.toFuture()
    }
}