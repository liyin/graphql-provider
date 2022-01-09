package org.babyfish.graphql.provider.kimmer.runtime.asm

import org.babyfish.graphql.provider.kimmer.meta.ImmutableProp
import org.babyfish.graphql.provider.kimmer.meta.ImmutableType
import org.springframework.asm.*
import java.lang.StringBuilder
import kotlin.reflect.KClass

internal inline fun implInternalName(immutableType: ImmutableType): String =
    "${Type.getInternalName(immutableType.kotlinType.java)}{Implementation}"

internal inline fun draftImplInternalName(immutableType: ImmutableType): String =
    "${Type.getInternalName(immutableType.kotlinType.java)}{DraftImplementation}"

internal inline fun syncDraftImplInternalName(immutableType: ImmutableType): String =
    "${Type.getInternalName(immutableType.kotlinType.java)}{SyncDraftImplementation}"

internal inline fun loadedName(type: ImmutableProp): String =
    "${type.name}{Loaded}"

internal inline fun throwableName(type: ImmutableProp): String =
    "${type.name}{Throwable}"

internal inline fun draftContextName(): String =
    "{draftContext}"

internal inline fun baseName(): String =
    "base"

internal inline fun modifiedName(): String =
    "modified"

internal fun ClassLoader.defineClass(bytecode: ByteArray): Class<*> =
    DEFINE_CLASS.invoke(this, bytecode, 0, bytecode.size) as Class<*>

private val DEFINE_CLASS = ClassLoader::class.java.getDeclaredMethod(
    "defineClass",
    ByteArray::class.java,
    Int::class.javaPrimitiveType,
    Int::class.javaPrimitiveType,
).also {
    it.isAccessible = true
}

internal const val BYTECODE_VERSION = Opcodes.V1_8

internal val OBJECT_INTERNAL_NAME = Type.getInternalName(Object::class.java)

internal fun ClassVisitor.writeField(
    access: Int,
    name: String,
    desc: String
) {
    visitField(
        access,
        name,
        desc,
        null,
        null
    )
}

internal fun ClassVisitor.writeMethod(
    access: Int,
    name: String,
    desc: String,
    block: MethodVisitor.() -> Unit
) {
    visitMethod(
        access,
        name,
        desc,
        null,
        null
    ).apply {
        visitCode()
        block()
        visitMaxs(0, 0)
        visitEnd()
    }
}

internal fun MethodVisitor.visitCond(
    opcode: Int,
    block: MethodVisitor.() -> Unit
) {
    visitCond(opcode, block, null)
}

internal fun MethodVisitor.visitCond(
    opcode: Int,
    block: MethodVisitor.() -> Unit,
    elseBlock: (MethodVisitor.() -> Unit)?
) {
    val label = Label()
    val endIfLabel = elseBlock?.let {
        Label()
    }
    visitJumpInsn(
        opcode,
        label
    )
    block()
    endIfLabel?.let {
        visitJumpInsn(Opcodes.GOTO, it)
    }
    visitLabel(label)
    elseBlock?.let {
        it()
    }
    endIfLabel?.let {
        visitLabel(it)
    }
}

internal fun MethodVisitor.visitThrow(
    type: KClass<out Throwable>,
    message: String
) {
    val internalName = Type.getInternalName(type.java)
    visitTypeInsn(Opcodes.NEW, internalName)
    visitInsn(Opcodes.DUP)
    visitLdcInsn(message)
    visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        internalName,
        "<init>",
        "(Ljava/lang/String;)V",
        false
    )
    visitInsn(Opcodes.ATHROW)
}

internal fun MethodVisitor.visitLoad(type: Class<*>, local: Int) {
    visitVarInsn(
        when {
            type === Double::class.javaPrimitiveType -> Opcodes.DLOAD
            type === Float::class.javaPrimitiveType -> Opcodes.FLOAD
            type === Long::class.javaPrimitiveType -> Opcodes.LLOAD
            type.isPrimitive -> Opcodes.ILOAD
            else -> Opcodes.ALOAD
        },
        local
    )
}

internal fun MethodVisitor.visitStore(type: Class<*>, local: Int) {
    visitVarInsn(
        when {
            type === Double::class.javaPrimitiveType -> Opcodes.DSTORE
            type === Float::class.javaPrimitiveType -> Opcodes.FSTORE
            type === Long::class.javaPrimitiveType -> Opcodes.LSTORE
            type.isPrimitive -> Opcodes.ISTORE
            else -> Opcodes.ASTORE
        },
        local
    )
}

internal fun MethodVisitor.visitReturn(type: Class<*>) {
    visitInsn(
        when {
            type === Double::class.javaPrimitiveType -> Opcodes.DRETURN
            type === Float::class.javaPrimitiveType -> Opcodes.FRETURN
            type === Long::class.javaPrimitiveType -> Opcodes.LRETURN
            type.isPrimitive -> Opcodes.IRETURN
            type === Void::class.java -> Opcodes.RETURN
            else -> Opcodes.ARETURN
        }
    )
}

internal fun MethodVisitor.visitPropNameSwitch(
    type: ImmutableType,
    loadNameBlock: MethodVisitor.() -> Unit,
    matchedBlock: MethodVisitor.(prop: ImmutableProp, switchEndLabel: Label) -> Unit
) {
    val switchEndLabel = Label()
    val propGroups = type.props.values.groupBy { it.name.hashCode() }.toSortedMap()
    if (propGroups.isNotEmpty()) {
        val defaultLabel = Label()
        val keys = propGroups.keys.let {
            val arr = IntArray(it.size)
            var index = 0
            for (key in it) {
                arr[index++] = key
            }
            arr
        }
        val labels = propGroups.keys.map { Label() }.toTypedArray()
        loadNameBlock()
        visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Object",
            "hashCode",
            "()I",
            false
        )
        visitLookupSwitchInsn(
            defaultLabel,
            keys,
            labels
        )
        for (i in labels.indices) {
            visitLabel(labels[i])
            val props = propGroups[keys[i]]!!
            for (prop in props) {
                visitLdcInsn(prop.name)
                loadNameBlock()
                visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/Object",
                    "equals",
                    "(Ljava/lang/Object;)Z",
                    false
                )
                visitJumpInsn(Opcodes.IFEQ, defaultLabel)
                matchedBlock(prop, switchEndLabel)
            }
        }
        visitLabel(defaultLabel)
    }
    visitTypeInsn(Opcodes.NEW, Type.getInternalName(IllegalArgumentException::class.java))
    visitInsn(Opcodes.DUP)
    visitString(
        { visitLdcInsn("Illegal property name \"") },
        loadNameBlock,
        { visitLdcInsn("\"") }
    )
    visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        Type.getInternalName(IllegalArgumentException::class.java),
        "<init>",
        "(Ljava/lang/String;)V",
        false
    )
    visitInsn(Opcodes.ATHROW)
    visitLabel(switchEndLabel)
    visitJumpInsn(Opcodes.GOTO, switchEndLabel)
}

internal fun MethodVisitor.visitString(vararg blocks: MethodVisitor.() -> Unit) {
    visitTypeInsn(Opcodes.NEW, Type.getInternalName(StringBuilder::class.java))
    visitInsn(Opcodes.DUP)
    visitMethodInsn(
        Opcodes.INVOKESPECIAL,
        "java/lang/StringBuilder",
        "<init>",
        "()V",
        false
    )
    for (block in blocks) {
        block()
        visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/StringBuilder",
            "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
            false
        )
    }
    visitMethodInsn(
        Opcodes.INVOKEVIRTUAL,
        "java/lang/StringBuilder",
        "toString",
        "()Ljava/lang/String;",
        false
    )
}

internal fun primitiveTuples(type: Class<*>): Pair<String, String> =
    when (type) {
        Boolean::class.javaPrimitiveType ->
            "Z" to "java/lang/Boolean"
        Char::class.javaPrimitiveType ->
            "C" to "java/lang/Character"
        Byte::class.javaPrimitiveType ->
            "B" to "java/lang/Byte"
        Short::class.javaPrimitiveType ->
            "S" to "java/lang/Short"
        Int::class.javaPrimitiveType ->
            "I" to "java/lang/Integer"
        Long::class.javaPrimitiveType ->
            "J" to "java/lang/Long"
        Float::class.javaPrimitiveType ->
            "F" to "java/lang/Float"
        Double::class.javaPrimitiveType ->
            "D" to "java/lang/Double"
        else ->
            "" to ""
    }

internal fun MethodVisitor.visitBox(type: Class<*>) {
    val (primitiveName, boxName) = primitiveTuples(type)
    if (primitiveName != "") {
        visitMethodInsn(
            Opcodes.INVOKESTATIC,
            boxName,
            "valueOf",
            "($primitiveName)L$boxName;",
            false
        )
    }
}

internal fun MethodVisitor.visitChanged(
    prop: ImmutableProp,
    shadow: Shallow,
    block: MethodVisitor.() -> Unit
) {
    val type = prop.returnType.java
    if (type.isPrimitive) {
        val cmp = when (type) {
            Double::class.javaPrimitiveType -> Opcodes.DCMPL
            Float::class.javaPrimitiveType -> Opcodes.FCMPL
            Long::class.javaPrimitiveType -> Opcodes.LCMP
            else -> null
        }
        if (cmp !== null) {
            visitInsn(cmp)
            visitCond(Opcodes.IFEQ) {
                block()
            }
        } else {
            visitCond(Opcodes.IF_ICMPEQ) {
                block()
            }
        }
    } else {
        val deepEqualBock: () -> Unit = {
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/util/Objects",
                "equals",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                false
            )
            visitCond(Opcodes.IFNE) {
                block()
            }
        }
        if (prop.targetType !== null) {
            if (shadow is Shallow.Dynamic) {
                shadow.block(this)
                visitCond(
                    Opcodes.IFNE,
                    { deepEqualBock() },
                    {
                        visitCond(Opcodes.IF_ACMPEQ) {
                            block()
                        }
                    }
                )
            } else if (shadow is Shallow.Static){
                if (shadow.value) {
                    visitCond(Opcodes.IF_ACMPEQ) {
                        block()
                    }
                } else {
                    deepEqualBock()
                }
            }
        } else {
            deepEqualBock()
        }
    }
}

interface Shallow {
    companion object {
        fun dynamic(block: MethodVisitor.() -> Unit) = Dynamic(block)
        fun static(value: Boolean) = Static(value)
    }
    class Dynamic(val block: MethodVisitor.() -> Unit): Shallow
    class Static(val value: Boolean): Shallow
}