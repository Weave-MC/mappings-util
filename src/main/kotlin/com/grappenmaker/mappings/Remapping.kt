package com.grappenmaker.mappings

import org.objectweb.asm.*
import org.objectweb.asm.commons.*
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

public class AccessWideningVisitor(parent: ClassVisitor) : ClassVisitor(Opcodes.ASM9, parent) {
    private fun Int.widen() = this and (Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED).inv() or Opcodes.ACC_PUBLIC
    private fun Int.removeFinal() = this and Opcodes.ACC_FINAL.inv()

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<String>?
    ) {
        super.visit(version, access.widen().removeFinal(), name, signature, superName, interfaces)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<String>?
    ): MethodVisitor = super.visitMethod(access.widen().removeFinal(), name, descriptor, signature, exceptions)

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?
    ): FieldVisitor = super.visitField(access.widen(), name, descriptor, signature, value)
}

/**
 * A [ClassRemapper] that is aware of the remapping of Invoke Dynamic instructions for lambdas.
 */
public class LambdaAwareRemapper(parent: ClassVisitor, remapper: Remapper) : ClassRemapper(Opcodes.ASM9, parent, remapper) {
    override fun createMethodRemapper(parent: MethodVisitor): MethodRemapper =
        LambdaAwareMethodRemapper(parent, remapper)
}

/**
 * A [MethodRemapper] that is aware of the remapping of Invoke Dynamic instructions for lambdas.
 */
public open class LambdaAwareMethodRemapper(
    private val parent: MethodVisitor,
    remapper: Remapper
) : MethodRemapper(Opcodes.ASM9, parent, remapper) {
    override fun visitInvokeDynamicInsn(name: String, descriptor: String, handle: Handle, vararg args: Any) {
        val remappedName = if (
            handle.owner == "java/lang/invoke/LambdaMetafactory" &&
            (handle.name == "metafactory" || handle.name == "altMetafactory")
        ) {
            // Lambda, so we need to rename it... weird edge case, maybe ASM issue?
            // LambdaMetafactory just causes an IncompatibleClassChangeError if the lambda is invalid
            // Does it assume correct compile time? odd.
            remapper.mapMethodName(
                Type.getReturnType(descriptor).internalName,
                name,
                (args.first() as Type).descriptor
            )
        } else name

        parent.visitInvokeDynamicInsn(
            remappedName,
            remapper.mapMethodDesc(descriptor),
            remapper.mapValue(handle) as Handle,
            *args.map { remapper.mapValue(it) }.toTypedArray()
        )
    }
}

/**
 * A [Remapper] for [Mappings], which is capable of using inheritance information from classes
 * (the implementor may choose to cache them) to resolve mapping data.
 *
 * Maps between [from] and [to] namespaces. If [shouldRemapDesc] is true (which it is by default if the [from]
 * namespace is not the first namespace in the mappings), this [MappingsRemapper] will remap the descriptors
 * of methods before passing them on to the mappings, in order to find the correct overload.
 *
 * [loader] should return the bytes for a class file with a given internal name, whether that is in a jar file,
 * this JVMs system class loader, or another resource. If [loader] returns `null`, the remapper considers the
 * class file not present/missing/irrelevant.
 *
 * @see [ClasspathLoaders] for default implementations of [loader]
 */
public class MappingsRemapper(
    public val mappings: Mappings,
    public val from: String,
    public val to: String,
    private val shouldRemapDesc: Boolean = mappings.namespaces.indexOf(from) != 0,
    private val loader: (name: String) -> ByteArray?
) : Remapper() {
    private val map = mappings.asASMMapping(from, to)
    private val baseMapper by lazy {
        MappingsRemapper(mappings, from, mappings.namespaces.first(), shouldRemapDesc = false, loader)
    }

    override fun map(internalName: String): String = map[internalName] ?: internalName
    override fun mapMethodName(owner: String, name: String, desc: String): String {
        if (name == "<init>" || name == "<clinit>") return name

        // Source: https://github.com/FabricMC/tiny-remapper/blob/d14e8f99800e7f6f222f820bed04732deccf5109/src/main/java/net/fabricmc/tinyremapper/AsmRemapper.java#L74
        return if (desc.startsWith("(")) {
            val actualDesc = if (shouldRemapDesc) baseMapper.mapMethodDesc(desc) else desc
            walk(owner, name) { map["$it.$name$actualDesc"] }
        } else mapFieldName(owner, name, desc)
    }

    override fun mapFieldName(owner: String, name: String, desc: String?): String =
        walk(owner, name) { map["$it.$name"] }

    override fun mapRecordComponentName(owner: String, name: String, desc: String): String =
        mapFieldName(owner, name, desc)

    override fun mapSignature(signature: String?, typeSignature: Boolean): String? =
        if (signature?.isEmpty() == true) null else super.mapSignature(signature, typeSignature)

    private inline fun walk(
        owner: String,
        name: String,
        applicator: (owner: String) -> String?
    ) = walkInheritance(loader, owner) { applicator(it) != null }?.let(applicator) ?: name

    /**
     * Returns a [MappingsRemapper] that reverses the changes of this [MappingsRemapper].
     *
     * Note that [loader] is by default set to the already passed loader, but it might be incorrect
     * depending on the implementation of [loader] in the original [MappingsRemapper]. Make sure to pass a new
     * implementation if inheritance data matters to you and the original [loader] could not handle different
     * namespaced names.
     */
    public fun reverse(loader: (name: String) -> ByteArray? = this.loader): MappingsRemapper =
        MappingsRemapper(mappings, to, from, loader = loader)
}

private data class MethodDeclaration(
    val owner: String,
    val name: String,
    val descriptor: String,
    val returnType: Type,
    val parameterTypes: List<Type>
)
private data class FieldDeclaration(
    val owner: String,
    val name: String
)
public class SimpleAnnotationNode(
    parent: AnnotationVisitor?,
    public val descriptor: String?
): AnnotationVisitor(Opcodes.ASM9, parent) {
    public val values: MutableList<Pair<String?, Any?>> = mutableListOf()
    override fun visit(name: String?, value: Any?) {
        values += name to value
        super.visit(name, value)
    }
}

public open class ModJarRemapper(
    parent: ClassVisitor,
    remapper: Remapper
): ClassRemapper(Opcodes.ASM9, parent, remapper) {
    private var annotationNode: SimpleAnnotationNode? = null
    override fun createAnnotationRemapper(
        descriptor: String?,
        parent: AnnotationVisitor?
    ): AnnotationVisitor {
        if (descriptor != "Lnet/weavemc/api/mixin/Mixin;")
            return super.createAnnotationRemapper(descriptor, parent)

        val node = SimpleAnnotationNode(parent, descriptor)
        annotationNode = node

        return node
    }
    override fun createMethodRemapper(parent: MethodVisitor): MethodVisitor {
        if (annotationNode == null)
            return super.createMethodRemapper(parent)

        val targetClass: String = annotationNode!!.values[0].toString()
        // substring targetClass to remove 'L' and ';' from the full name
        val formattedTargetClass = targetClass.substring(1, targetClass.length - 1)
        return ModMethodRemapper(formattedTargetClass, parent, remapper)
    }
}
public open class ModMethodRemapper(
    private val targetMixinClass: String,
    private val parent: MethodVisitor,
    remapper: Remapper
): LambdaAwareMethodRemapper(parent, remapper) {
    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        val visitor = parent.visitAnnotation(descriptor, visible)
        return if (visitor != null) MixinAnnotationRemapper(targetMixinClass, visitor, descriptor, remapper)
        else super.visitAnnotation(descriptor, visible)
    }
}
public class MixinAnnotationRemapper(
    private val targetMixinClass: String,
    private val parent: AnnotationVisitor,
    descriptor: String?,
    remapper: Remapper
): AnnotationRemapper(Opcodes.ASM9, descriptor, parent, remapper) {
    override fun visit(name: String, value: Any) {
        val remappedValue = when {
            value is String && shouldRemap() -> {
                val annotationName = descriptor.substringAfterLast('/')
                when (name) {
                    "method" -> {
                        if (!value.isMethod())
                            error("Failed to identify $value as a method in $annotationName annotation")

                        val method = parseMethod(targetMixinClass, value)
                            ?: error("Failed to parse mixin method value $value of annotation $annotationName")

                        val mappedName = remapper.mapMethodName(method.owner, method.name, method.descriptor)
                        val mappedDesc = remapper.mapMethodDesc(method.descriptor)
                        mappedName + mappedDesc
                    }
                    "field" -> {
                        if (value.isMethod())
                            error("Identified $value as a method when field is expected in $annotationName annotation")

                        remapper.mapFieldName(targetMixinClass, value, null)
                    }
                    "target" -> {
                        name.parseAndRemapTarget()
                    }
                    else -> value
                }
            } else -> value
        }
        parent.visit(name, remappedValue)
    }

    private fun String.isMethod(): Boolean {
        val startIndex = this.indexOf('(')
        val endIndex = this.indexOf(')')
        return startIndex != -1 && endIndex != -1 && startIndex < endIndex
    }
    private fun String.parseAndRemapTarget(): String {
        return if (this.isMethod()) {
            val method = parseTargetMethod(this) ?: return this
            val mappedName = remapper.mapMethodName(method.owner, method.name, method.descriptor)
            val mappedDesc = remapper.mapMethodDesc(method.descriptor)
            mappedName + mappedDesc
        } else {
            val field = parseTargetField(this) ?: return this
            remapper.mapFieldName(field.owner, field.name, null)
        }
    }
    private fun shouldRemap(): Boolean =
        descriptor != null && descriptor.startsWith("Lnet/weavemc/api/mixin")
}

/**
 * Remaps a .jar file [input] to an [output] file, using [mappings], between namespaces [from] and [to].
 * If inheritance info from classpath jars matters, you should pass all of the relevant [classpath] jars.
 */
public fun remapJar(
    mappings: Mappings,
    input: File,
    output: File,
    from: String = "official",
    to: String = "named",
    classpath: List<File> = listOf(),
) {
    val cache = hashMapOf<String, ByteArray?>()
    val jarsToUse = (classpath + input).map { JarFile(it) }
    val lookup = jarsToUse.flatMap { j ->
        j.entries().asSequence().filter { it.name.endsWith(".class") }
            .map { it.name.dropLast(6) to { j.getInputStream(it).readBytes() } }
    }.toMap()

    JarFile(input).use { jar ->
        JarOutputStream(output.outputStream()).use { out ->
            val (classes, resources) = jar.entries().asSequence().partition { it.name.endsWith(".class") }

            fun write(name: String, bytes: ByteArray) {
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
            }

            resources.filterNot { it.name.endsWith(".RSA") || it.name.endsWith(".SF") }
                .forEach { write(it.name, jar.getInputStream(it).readBytes()) }

            val remapper = MappingsRemapper(
                mappings, from, to,
                loader = { name -> if (name in lookup) cache.getOrPut(name) { lookup.getValue(name)() } else null }
            )

            classes.forEach { entry ->
                val reader = ClassReader(jar.getInputStream(entry).readBytes())
                val writer = ClassWriter(reader, 0)
                reader.accept(LambdaAwareRemapper(writer, remapper), 0)

                write("${remapper.map(reader.className)}.class", writer.toByteArray())
            }
        }
    }

    jarsToUse.forEach { it.close() }
}

public fun remapModJar(
    mappings: Mappings,
    input: File,
    output: File,
    from: String = "official",
    to: String = "named",
    lookup: (String) -> ByteArray?,
) {
    JarFile(input).use { jar ->
        JarOutputStream(output.outputStream()).use { out ->
            val (classes, resources) = jar.entries().asSequence().partition { it.name.endsWith(".class") }

            fun write(name: String, bytes: ByteArray) {
                out.putNextEntry(JarEntry(name))
                out.write(bytes)
            }

            resources.filterNot { it.name.endsWith(".RSA") || it.name.endsWith(".SF") }
                .forEach { write(it.name, jar.getInputStream(it).readBytes()) }

            val remapper = MappingsRemapper(
                mappings, from, to,
                loader = lookup
            )

            classes.forEach { entry ->
                val reader = ClassReader(jar.getInputStream(entry).readBytes())
                val writer = ClassWriter(reader, 0)

                reader.accept(ModJarRemapper(writer, remapper), 0)

                write("${remapper.map(reader.className)}.class", writer.toByteArray())
            }
        }
    }
}

public fun ByteArray.remap(remapper: Remapper): ByteArray {
    val reader = ClassReader(this)

    val writer = ClassWriter(reader, 0)
    reader.accept(LambdaAwareRemapper(writer, remapper), 0)

    return writer.toByteArray()
}
/**
 * Parses a method with an already known owner
 * @param owner The inferred owner
 * @param methodDeclaration The method declaration in methodName(methodDescriptor)methodReturnType format
 * @return MethodDeclaration data class including the parsed method name, descriptor, and the passed owner param
 */
private fun parseMethod(owner: String, methodDeclaration: String): MethodDeclaration? {
    try {
        val methodName = methodDeclaration.substringBefore('(')
        val methodDesc = methodDeclaration.drop(methodName.length)
        val methodType = Type.getMethodType(methodDesc)
        return MethodDeclaration(
            owner,
            methodName,
            methodDesc,
            methodType.returnType,
            methodType.argumentTypes.toList()
        )
    } catch (ex: Exception) {
        println("Failed to parse method declaration: $methodDeclaration")
        ex.printStackTrace()
    }
    return null
}
private fun parseTargetField(fieldDeclaration: String): FieldDeclaration? {
    try {
        val (classPath, fieldName) = fieldDeclaration.splitAround('.')
        return FieldDeclaration(classPath, fieldName)
    } catch (ex: Exception) {
        println("Failed to parse field declaration: $fieldDeclaration")
        ex.printStackTrace()
    }
    return null
}

/**
 * Parses a method without an already known owner.
 * Typically used for Mixin @At target values, where the owner cannot be inferred.
 * @param methodDeclaration The method declaration in methodOwner.methodName(methodDescriptor)methodReturnType format
 * @return MethodDeclaration data class including the parsed method name, descriptor, and owner
 */
private fun parseTargetMethod(methodDeclaration: String): MethodDeclaration? {
    try {
        val (classPath, fullMethod) = methodDeclaration.splitAround('.')
        val methodName = fullMethod.substringBefore('(')
        val methodDesc = fullMethod.drop(methodName.length)
        val methodType = Type.getMethodType(methodDesc)
        return MethodDeclaration(
            classPath,
            methodName,
            methodDesc,
            methodType.returnType,
            methodType.argumentTypes.toList()
        )
    } catch (ex: Exception) {
        println("Failed to parse method declaration: $methodDeclaration")
        ex.printStackTrace()
    }
    return null
}