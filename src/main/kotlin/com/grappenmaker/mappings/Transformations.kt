package com.grappenmaker.mappings

/**
 * Transforms this [Mappings] structure to a generic mappings implementation that maps between [from] and [to].
 */
public fun Mappings.extractNamespaces(from: String, to: String): Mappings {
    val fromIndex = namespace(from)
    val toIndex = namespace(to)
    val remapper = MappingsRemapper(this, namespaces.first(), from, shouldRemapDesc = false) { null }

    return GenericMappings(
        namespaces = listOf(from, to),
        classes = classes.map { c ->
            c.copy(
                names = listOf(c.names[fromIndex], c.names[toIndex]),
                fields = c.fields.map {
                    it.copy(
                        names = listOf(it.names[fromIndex], it.names[toIndex]),
                        desc = remapper.mapDesc(it.desc)
                    )
                },
                methods = c.methods.map {
                    it.copy(
                        names = listOf(it.names[fromIndex], it.names[toIndex]),
                        desc = remapper.mapMethodDesc(it.desc)
                    )
                }
            )
        }
    )
}

/**
 * Swaps out the names for the namespaces in this [Mappings] data structure.
 */
public fun Mappings.renameNamespaces(to: List<String>): Mappings {
    require(to.size == namespaces.size) { "namespace length does not match" }
    return GenericMappings(to, classes)
}

/**
 * Swaps out the names for the namespaces in this [Mappings] data structure.
 */
public fun Mappings.renameNamespaces(vararg to: String): Mappings = renameNamespaces(to.toList())

/**
 * Swaps out the names for the namespaces in this [Mappings] data structure, by reordering. All names in [order] should
 * appear in the [Mappings.namespaces]. Duplicate names are allowed.
 */
public fun Mappings.reorderNamespaces(vararg order: String): Mappings = reorderNamespaces(order.toList())

/**
 * Swaps out the names for the namespaces in this [Mappings] data structure, by reordering. All names in [order] should
 * appear in the [Mappings.namespaces]. Duplicate names are allowed.
 */
public fun Mappings.reorderNamespaces(order: List<String>): Mappings {
    require(order.size == namespaces.size) { "namespace length does not match" }

    val indices = order.map {
        namespaces.indexOf(it).also { i ->
            require(i != -1) { "Namespace $it missing in namespaces: $namespaces" }
        }
    }

    val remapper = MappingsRemapper(this, namespaces.first(), order.first()) { null }

    return GenericMappings(
        namespaces = order,
        classes = classes.map { c ->
            c.copy(
                names = indices.map { c.names[it] },
                fields = c.fields.map { f ->
                    f.copy(
                        names = indices.map { f.names[it] },
                        desc = remapper.mapDesc(f.desc)
                    )
                },
                methods = c.methods.map { m ->
                    m.copy(
                        names = indices.map { m.names[it] },
                        desc = remapper.mapMethodDesc(m.desc)
                    )
                },
            )
        },
    )
}

private fun <T> Set<T>.disjointTo(other: Set<T>) = (this - other) + (other - this)
private fun <T> Set<T>.assertEqual(other: Set<T>, name: String) {
    val disjoint = disjointTo(other)
    require(disjoint.isEmpty()) { "${disjoint.size} $name are missing (requireMatch)" }
}

/**
 * Joins together this [Mappings] with [otherMappings], by matching on [intermediateNamespace].
 * If [requireMatch] is true, this method will throw an exception when no method or field or class is found
 */
public fun Mappings.join(
    otherMappings: Mappings,
    intermediateNamespace: String,
    requireMatch: Boolean = false,
): Mappings {
    val firstIntermediaryId = namespace(intermediateNamespace)
    val secondIntermediaryId = otherMappings.namespace(intermediateNamespace)
    val firstByName = classes.associateBy { it.names[firstIntermediaryId] }
    val secondByName = otherMappings.classes.associateBy { it.names[secondIntermediaryId] }

    if (requireMatch) firstByName.keys.assertEqual(secondByName.keys, "classes")

    val otherNamespaces = (namespaces + otherMappings.namespaces).filterNot { it == intermediateNamespace }.distinct()
    val firstNs = otherNamespaces.mapNotNull { n -> namespaces.indexOf(n).takeIf { it != -1 } }
    val secondNs = otherNamespaces.mapNotNull { n -> otherMappings.namespaces.indexOf(n).takeIf { it != -1 } }
    val orderedNs = firstNs.map { namespaces[it] } +
            intermediateNamespace +
            secondNs.map { otherMappings.namespaces[it] }

    fun <T : Mapped> updateName(on: T?, intermediateName: String, ns: List<Int>) =
        if (on != null) ns.map(on.names::get) else ns.map { intermediateName }

    fun <T : Mapped> updateNames(first: T?, intermediateName: String, second: T?) =
        updateName(first, intermediateName, firstNs) + intermediateName + updateName(second, intermediateName, secondNs)

    val firstBaseRemapper = MappingsRemapper(
        mappings = this,
        from = namespaces.first(),
        to = intermediateNamespace,
        shouldRemapDesc = false
    ) { null }

    val secondBaseRemapper = MappingsRemapper(
        mappings = otherMappings,
        from = otherMappings.namespaces.first(),
        to = intermediateNamespace,
        shouldRemapDesc = false
    ) { null }

    val finalizeRemapper = MappingsRemapper(
        mappings = this,
        from = namespaces.first(),
        to = orderedNs.first(),
        shouldRemapDesc = false
    ) { null }

    val classesToConsider = firstByName.keys + secondByName.keys

    return GenericMappings(
        namespaces = orderedNs,
        classes = classesToConsider.map { intermediateName ->
            val firstClass = firstByName[intermediateName]
            val secondClass = secondByName[intermediateName]

            // TODO: DRY
            val firstFieldsByName = firstClass?.fields?.associateBy { it.names[firstIntermediaryId] } ?: emptyMap()
            val secondFieldsByName = secondClass?.fields?.associateBy { it.names[secondIntermediaryId] } ?: emptyMap()

            val firstMethodsBySig = firstClass?.methods?.associateBy {
                it.names[firstIntermediaryId] + firstBaseRemapper.mapMethodDesc(it.desc)
            } ?: emptyMap()

            val secondMethodsBySig = secondClass?.methods?.associateBy {
                it.names[secondIntermediaryId] + secondBaseRemapper.mapMethodDesc(it.desc)
            } ?: emptyMap()

            if (requireMatch) {
                firstFieldsByName.keys.assertEqual(secondFieldsByName.keys, "fields")
                firstMethodsBySig.keys.assertEqual(secondMethodsBySig.keys, "methods")
            }

            val fieldsToConsider = firstFieldsByName.keys + secondFieldsByName.keys
            val methodsToConsider = firstMethodsBySig.keys + secondMethodsBySig.keys

            MappedClass(
                names = updateNames(firstClass, intermediateName, secondClass),
                comments = (firstClass?.comments ?: emptyList()) + (secondClass?.comments ?: emptyList()),
                fields = fieldsToConsider.map { intermediateFieldName ->
                    val firstField = firstFieldsByName[intermediateFieldName]
                    val secondField = secondFieldsByName[intermediateFieldName]

                    MappedField(
                        names = updateNames(firstField, intermediateFieldName, secondField),
                        comments = (firstField?.comments ?: emptyList()) + (secondField?.comments ?: emptyList()),
                        desc = (firstField ?: secondField)?.desc?.let(finalizeRemapper::mapDesc)
                    )
                },
                methods = methodsToConsider.map { sig ->
                    val intermediateMethodName = sig.substringBefore('(')
                    val desc = sig.drop(intermediateMethodName.length)
                    val firstMethod = firstMethodsBySig[sig]
                    val secondMethod = secondMethodsBySig[sig]

                    MappedMethod(
                        names = updateNames(firstMethod, intermediateMethodName, secondMethod),
                        comments = (firstMethod?.comments ?: emptyList()) + (secondMethod?.comments ?: emptyList()),
                        desc = finalizeRemapper.mapMethodDesc(desc),
                        parameters = emptyList(),
                        variables = emptyList(),
                    )
                }
            )
        }
    )
}

/**
 * Joins together a list of [Mappings].
 * Note: all namespaces are kept, in order to be able to reduce the mappings nicely without a lot of overhead.
 * If you want to exclude certain namespaces, use [Mappings.filterNamespaces]
 *
 * @see [Mappings.join]
 */
public fun List<Mappings>.join(
    intermediateNamespace: String,
    requireMatch: Boolean = false
): Mappings = reduce { acc, curr -> acc.join(curr, intermediateNamespace, requireMatch) }

/**
 * Filters these [Mappings] to only contain namespaces that are in [allowed]
 */
public fun Mappings.filterNamespaces(vararg allowed: String): Mappings = filterNamespaces(allowed.toSet())

/**
 * Filters these [Mappings] to only contain namespaces that are in [allowed]
 */
public fun Mappings.filterNamespaces(allowed: Set<String>, allowDuplicates: Boolean = false): Mappings {
    val indices = mutableListOf<Int>()
    val seen = hashSetOf<String>()
    namespaces.intersect(allowed).forEachIndexed { idx, n -> if (allowDuplicates || seen.add(n)) indices += idx }

    fun <T : Mapped> T.update() = indices.map(names::get)

    return GenericMappings(
        namespaces = indices.map { namespaces[it] },
        classes = classes.map { c ->
            c.copy(
                names = c.update(),
                fields = c.fields.map { it.copy(names = it.update()) },
                methods = c.methods.map { it.copy(names = it.update()) },
            )
        }
    )
}

public fun Mappings.deduplicateNamespaces() = filterNamespaces(namespaces.toSet())
public inline fun Mappings.filterClasses(block: (MappedClass) -> Boolean) =
    GenericMappings(namespaces, classes.filter(block))

public inline fun Mappings.mapClasses(block: (MappedClass) -> MappedClass) =
    GenericMappings(namespaces, classes.map(block))