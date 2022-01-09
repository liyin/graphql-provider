package org.babyfish.graphql.provider.kimmer.ksp

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import java.io.OutputStreamWriter

class DraftGenerator(
    private val codeGenerator: CodeGenerator,
    private val sysTypes: SysTypes,
    private val file: KSFile,
    private val modelClassDeclarations: List<KSClassDeclaration>
) {
    fun generate(files: List<KSFile>) {
        val draftFileName =
            file.fileName.let {
                var lastDotIndex = it.lastIndexOf('.')
                if (lastDotIndex === -1) {
                    "${it}Draft"
                } else {
                    "${it.substring(0, lastDotIndex)}${DRAFT_SUFFIX}"
                }
            }
        codeGenerator.createNewFile(
            Dependencies(false, *files.toTypedArray()),
            file.packageName.asString(),
            draftFileName
        ).use {
            val fileSpec = FileSpec
                .builder(
                    file.packageName.asString(),
                    draftFileName
                ).apply {
                    for (classDeclaration in modelClassDeclarations) {
                        addType(classDeclaration)
                    }
                }.build()
            val writer = OutputStreamWriter(it, Charsets.UTF_8)
            fileSpec.writeTo(writer)
            writer.flush()
        }
    }

    private fun FileSpec.Builder.addType(classDeclaration: KSClassDeclaration) {
        addType(
            TypeSpec.interfaceBuilder(
                "${classDeclaration.simpleName.asString()}${DRAFT_SUFFIX}"
            ).apply {
                addTypeVariable(
                    TypeVariableName("T", classDeclaration.asClassName())
                )
                addSuperinterface(classDeclaration.asClassName())
                for (superType in classDeclaration.superTypes) {
                    val st = superType.resolve()
                    if (sysTypes.immutableType.isAssignableFrom(st) && st.arguments.isEmpty()) {
                        if (st === sysTypes.immutableType) {
                            addSuperinterface(
                                ClassName(KIMMER_PACKAGE, "Draft")
                                    .parameterizedBy(TypeVariableName("T"))
                            )
                        } else {
                            addSuperinterface(
                                (st.declaration as KSClassDeclaration).asClassName {
                                    "$it$DRAFT_SUFFIX"
                                }.parameterizedBy(TypeVariableName("T"))
                            )
                        }
                    }
                }
                for (prop in classDeclaration.getDeclaredProperties()) {
                    addMembers(prop)
                }
                addNestedTypes(classDeclaration)
            }.build()
        )
    }

    private fun TypeSpec.Builder.addMembers(prop: KSPropertyDeclaration) {
        if (prop.isMutable) {
            throw GeneratorException("The property '${prop.qualifiedName!!.asString()}' cannot be mutable")
        }
        val meta = prop.findPropMeta()

        addProperty(
            PropertySpec.builder(
                prop.simpleName.asString(),
                meta.nullableReturnType,
                KModifier.OVERRIDE
            ).apply {
                mutable(true)
            }.build()
        )

        if (meta.targetDeclaration !== null) {
            addFunction(
                FunSpec
                    .builder(prop.simpleName.asString())
                    .apply {
                        modifiers += KModifier.ABSTRACT
                        returns(meta.nonNullReturnType)
                    }
                    .build()
            )
        }
    }

    private fun KSPropertyDeclaration.findPropMeta(): PropMeta {
        val nullableType = type.resolve()
        val nonNullType = nullableType.makeNotNullable()
        if (sysTypes.mapType.isAssignableFrom(nonNullType)) {
            throw GeneratorException("The property '${qualifiedName!!.asString()}' cannot be map")
        }
        val isList = sysTypes.collectionType.isAssignableFrom(nonNullType)
        val targetType = when {
            sysTypes.connectionType.isAssignableFrom(nonNullType) -> {
                val declaration = nonNullType.declaration
                if (nonNullType.arguments.isNotEmpty() ||
                    declaration !is KSClassDeclaration ||
                    declaration.classKind !== ClassKind.INTERFACE
                ) {
                    throw GeneratorException(
                        "The property '${qualifiedName!!.asString()}' must " +
                            "be derived interface of connection without type arguments"
                    )
                }
                nonNullType.findConnectionNodeType()?.also {
                    if (it.isMarkedNullable) {
                        throw GeneratorException(
                            "The connection type '${nonNullType.declaration.qualifiedName!!.asString()}' " +
                                "of the property '${qualifiedName!!.asString()}' does not support nullable node"
                        )
                    }
                } ?: throw GeneratorException(
                    "Cannot get connection node type from '${qualifiedName!!.asString()}'"
                )
            }
            isList -> {
                if (nonNullType.declaration != sysTypes.listType.declaration) {
                    throw GeneratorException("The property '${qualifiedName!!.asString()}' must be list")
                }
                nonNullType.arguments[0].type?.resolve() ?: throw GeneratorException(
                    "Cannot get list element type from '${qualifiedName!!.asString()}'"
                )
            }
            sysTypes.immutableType.isAssignableFrom(nonNullType) -> {
                nonNullType
            }
            else -> null
        } ?: return PropMeta(this, nullableType.isMarkedNullable)

        val declaration = targetType.declaration
        if (!sysTypes.immutableType.isAssignableFrom(targetType) ||
            declaration !is KSClassDeclaration ||
            declaration.classKind != ClassKind.INTERFACE ||
            declaration.typeParameters.isNotEmpty()
        ) {
            throw GeneratorException(
                "The property '${qualifiedName!!.asString()}' is not valid association, " +
                    "its target type '${declaration.qualifiedName!!.asString()}' is not " +
                    "interface extends Immutable"
            )
        }
        return PropMeta(
            this,
            nullableType.isMarkedNullable,
            declaration,
            isList,
            if (isList) {
                targetType.isMarkedNullable
            } else {
                !sysTypes.connectionType.isAssignableFrom(nonNullType) && nullableType.isMarkedNullable
            }
        )
    }

    private fun KSType.findConnectionNodeType(): KSType? {
        if (this == sysTypes.connectionType) {
            return arguments[0].type?.resolve()
        }
        val declaration = declaration
        if (declaration is KSClassDeclaration) {
            for (superType in declaration.superTypes) {
                val result = superType.resolve().findConnectionNodeType()
                if (result !== null) {
                    return result
                }
            }
        }
        return null
    }

    private fun TypeSpec.Builder.addNestedTypes(declaration: KSClassDeclaration) {
        for (prefix in finalDraftPrefixes) {
            addType(
                TypeSpec
                    .interfaceBuilder(prefix)
                    .apply {
                        addSuperinterface(
                            declaration.asClassName {
                                "$it$DRAFT_SUFFIX"
                            }.parameterizedBy(
                                declaration.asClassName()
                            )
                        )
                        addSuperinterface(
                            ClassName(KIMMER_PACKAGE, "${prefix}Draft")
                                .parameterizedBy(
                                    declaration.asClassName()
                                )
                        )
                    }
                    .build()
            )
        }
    }
}

private fun KSClassDeclaration.asClassName(simpleNameMapper: ((String) -> String)? = null): ClassName {
    val simpleName = simpleName.asString()
    if (simpleNameMapper === null) {
        return ClassName(packageName.asString(), simpleName)
    }
    return ClassName(packageName.asString(), simpleNameMapper(simpleName))
}

private data class PropMeta(
    val prop: KSPropertyDeclaration,
    val nullable: Boolean,
    val targetDeclaration: KSClassDeclaration? = null,
    val isList: Boolean = false,
    val isTargetNullable: Boolean = false
) {
    val nonNullReturnType: TypeName by lazy {
        targetDeclaration
            ?.run {
                val draftTypeName = asClassName {
                    "$it$DRAFT_SUFFIX"
                }.parameterizedBy(
                    WildcardTypeName.producerOf(asClassName())
                )
                if (isList) {
                    ClassName("kotlin.collections", "MutableList")
                        .parameterizedBy(draftTypeName)
                } else {
                    draftTypeName
                }
            }
            ?: (
                prop.type.resolve().declaration as? KSClassDeclaration
                    ?: throw GeneratorException("The property '${prop}' must returns class/interface type")
                ).asClassName()
    }

    val nullableReturnType: TypeName by lazy {
        targetDeclaration
            ?.run {
                val draftTypeName = asClassName {
                    "$it$DRAFT_SUFFIX"
                }.parameterizedBy(
                    WildcardTypeName.producerOf(asClassName())
                ).copy(nullable = isTargetNullable)
                if (isList) {
                    ClassName("kotlin.collections", "MutableList")
                        .parameterizedBy(draftTypeName)
                } else {
                    draftTypeName
                }
            }
            ?: (
                prop.type.resolve().declaration as? KSClassDeclaration
                    ?: throw GeneratorException("The property '${prop}' must returns class/interface type")
                ).asClassName().copy(nullable = isTargetNullable)
    }
}

private val finalDraftPrefixes = listOf("Sync", "Async")