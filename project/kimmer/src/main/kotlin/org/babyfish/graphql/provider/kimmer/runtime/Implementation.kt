package org.babyfish.graphql.provider.kimmer.runtime

import org.babyfish.graphql.provider.kimmer.Immutable
import org.babyfish.graphql.provider.kimmer.meta.ImmutableProp
import org.babyfish.graphql.provider.kimmer.meta.ImmutableType
import org.springframework.asm.ClassVisitor
import org.springframework.asm.ClassWriter
import org.springframework.asm.Opcodes
import org.springframework.asm.Type
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.reflect.jvm.javaMethod

internal fun implementationOf(type: Class<out Immutable>): Class<out Immutable> =
    cacheLock.read {
        cacheMap[type]
    } ?: cacheLock.write {
        cacheMap[type]
            ?: (createImplementation(ImmutableType.of(type)) as Class<out Immutable>).also {
                cacheMap[type] = it
            }
    }

private val cacheMap = WeakHashMap<Class<out Immutable>, Class<out Immutable>>()

private val cacheLock = ReentrantReadWriteLock()

private fun createImplementation(type: ImmutableType): Class<*> =
    ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        .apply {
            writeType(type)
        }
        .toByteArray()
        .let {
            type.kotlinType.java.classLoader.defineClass(it)
        } as Class<out Immutable>

private fun ClassVisitor.writeType(type: ImmutableType) {

    val implInternalName = implInternalName(type.kotlinType.java)

    visit(
        BYTECODE_VERSION,
        Opcodes.ACC_PUBLIC,
        implInternalName(type.kotlinType.java),
        null,
        OBJECT_INTERNAL_NAME,
        arrayOf(Type.getInternalName(type.kotlinType.java), Type.getInternalName(ImmutableImpl::class.java))
    )

    writeConstructor()
    writeCopyConstructor(type)

    for (prop in type.props.values) {
        writeProp(prop, implInternalName)
    }

    writeLoaded(type)

    visitEnd()
}

private fun ClassVisitor.writeConstructor() {
    writeMethod(
        Opcodes.ACC_PUBLIC,
        "<init>",
        "()V",
    ) {
        visitVarInsn(Opcodes.ALOAD, 0)
        visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            OBJECT_INTERNAL_NAME,
            "<init>",
            "()V",
            false
        )
        visitInsn(Opcodes.RETURN)
    }
}

private fun ClassVisitor.writeCopyConstructor(type: ImmutableType) {

    val implInternalName = implInternalName(type.kotlinType.java)

    writeMethod(
        Opcodes.ACC_PUBLIC,
        "<init>",
        "(L$implInternalName;)V"
    ) {

        val exceptionDesc = Type.getDescriptor(Throwable::class.java)

        visitVarInsn(Opcodes.ALOAD, 0)
        visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            OBJECT_INTERNAL_NAME,
            "<init>",
            "()V",
            false
        )

        for (prop in type.props.values) {

            val desc = Type.getDescriptor(prop.returnType.java)
            val loadedName = loadedName(prop)
            val exceptionName = exceptionName(prop)

            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(
                Opcodes.GETFIELD,
                implInternalName,
                prop.name,
                desc
            )
            visitFieldInsn(
                Opcodes.PUTFIELD,
                implInternalName,
                prop.name,
                desc
            )

            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(
                Opcodes.GETFIELD,
                implInternalName,
                loadedName,
                "Z"
            )
            visitFieldInsn(
                Opcodes.PUTFIELD,
                implInternalName,
                loadedName,
                "Z"
            )

            visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(
                Opcodes.GETFIELD,
                implInternalName,
                exceptionName,
                exceptionDesc
            )
            visitFieldInsn(
                Opcodes.PUTFIELD,
                implInternalName,
                exceptionName,
                exceptionDesc
            )
        }
        visitInsn(Opcodes.RETURN)
    }
}

private fun ClassVisitor.writeProp(prop: ImmutableProp, ownerInternalName: String) {

    val desc = Type.getDescriptor(prop.returnType.java)
    val loadedName = loadedName(prop)
    val exceptionName = exceptionName(prop)
    val exceptionDesc = Type.getDescriptor(Throwable::class.java)

    writeField(
        Opcodes.ACC_PROTECTED,
        prop.name,
        desc
    )

    writeField(
        Opcodes.ACC_PROTECTED,
        loadedName,
        "Z"
    )

    writeField(
        Opcodes.ACC_PROTECTED,
        exceptionName,
        exceptionDesc
    )

    val javaGetter = prop.kotlinProp.getter.javaMethod!!
    writeMethod(
        Opcodes.ACC_PUBLIC,
        javaGetter.name,
        Type.getMethodDescriptor(javaGetter)
    ) {

        visitVarInsn(Opcodes.ALOAD, 0)
        visitFieldInsn(Opcodes.GETFIELD, ownerInternalName, exceptionName, exceptionDesc)
        visitIf(
            Opcodes.IFNULL
        ) {
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, ownerInternalName, exceptionName, exceptionDesc)
            visitInsn(Opcodes.ATHROW)
        }

        visitVarInsn(Opcodes.ALOAD, 0)
        visitFieldInsn(Opcodes.GETFIELD, ownerInternalName, loadedName, "Z")
        visitIf(
            Opcodes.IFNE
        ) {
            visitThrow(UnloadedException::class, "The field '${prop.kotlinProp}' is unloaded")
        }

        visitVarInsn(Opcodes.ALOAD, 0)
        visitFieldInsn(Opcodes.GETFIELD, ownerInternalName, prop.name, desc)
        visitReturn(prop.returnType.java)
    }
}

private fun ClassVisitor.writeLoaded(type: ImmutableType) {
    writeMethod(
        Opcodes.ACC_PUBLIC,
        "{loaded}",
    "(Ljava/lang/String;)Z"
    ) {
        visitPropNameSwitch(type, { visitVarInsn(Opcodes.ALOAD, 1)}) { prop, _ ->
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(
                Opcodes.GETFIELD,
                implInternalName(type.kotlinType.java),
                loadedName(prop),
                "Z"
            )
            visitInsn(Opcodes.IRETURN)
        }
    }
}
