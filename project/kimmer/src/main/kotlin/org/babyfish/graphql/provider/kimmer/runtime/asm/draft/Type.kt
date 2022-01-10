package org.babyfish.graphql.provider.kimmer.runtime.asm.draft

import org.babyfish.graphql.provider.kimmer.runtime.*
import org.babyfish.graphql.provider.kimmer.runtime.asm.*
import org.springframework.asm.ClassVisitor
import org.springframework.asm.Opcodes
import org.springframework.asm.Type

internal fun ClassVisitor.writeType(args: GeneratorArgs) {
    visit(
        BYTECODE_VERSION,
        Opcodes.ACC_PROTECTED,
        args.draftImplInternalName,
        null,
        OBJECT_INTERNAL_NAME,
        arrayOf(args.draftInternalName, Type.getInternalName(DraftSpi::class.java))
    )

    writeField(
        Opcodes.ACC_PROTECTED,
        draftContextName(),
        Type.getDescriptor(DraftContext::class.java)
    )

    writeField(
        Opcodes.ACC_PROTECTED,
        baseName(),
        args.modelDescriptor
    )

    writeField(
        Opcodes.ACC_PRIVATE,
        modifiedName(),
        args.modelImplDescriptor
    )

    writeConstructor(args)
    for (prop in args.immutableType.props.values) {
        writeGetter(prop, args)
        writeSetter(prop, args)
        if (prop.targetType !== null) {
            writeCreator(prop, args)
        }
    }

    writeRuntimeType(args)
    writeLoaded(args)
    writeValue(args)

    writeHashCode(args)
    writeEquals(args)

    writeContext(args)
    writeResolve(args)

    writeUnload(args)

    visitEnd()
}