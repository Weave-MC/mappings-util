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
    val bySecondName = otherMappings.classes.associateBy { it.names[secondIntermediaryId] }

    val otherNamespaces = (namespaces + otherMappings.namespaces).filterNot { it == intermediateNamespace }.distinct()
    val firstNs = otherNamespaces.mapNotNull { n -> namespaces.indexOf(n).takeIf { it != -1 } }
    val secondNs = otherNamespaces.mapNotNull { n -> otherMappings.namespaces.indexOf(n).takeIf { it != -1 } }
    val orderedNs = firstNs.map { namespaces[it] } +
            intermediateNamespace +
            secondNs.map { otherMappings.namespaces[it] }

    fun <T : Mapped> T.updateNames(intermediateName: String, matching: T) =
        firstNs.map(names::get) + intermediateName + secondNs.map(matching.names::get)

    // Hack to not have to initialize <init> list every time we implement a missing <init> method mapping
    val initNames = List(orderedNs.size) { "<init>" }

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

    return GenericMappings(
        namespaces = orderedNs,
        classes = classes.mapNotNull { originalClass ->
            val intermediateName = originalClass.names[firstIntermediaryId]
            val matching = bySecondName[intermediateName]
                ?: if (requireMatch) error("No matching class found for ${originalClass.names}!")
                else return@mapNotNull null

            // Faster?
            val fieldsByName = matching.fields.associateBy { it.names[secondIntermediaryId] }
            val methodsBySig = matching.methods.associateBy {
                it.names[secondIntermediaryId] + secondBaseRemapper.mapMethodDesc(it.desc)
            }

            MappedClass(
                names = originalClass.updateNames(intermediateName, matching),
                comments = originalClass.comments + matching.comments,
                fields = originalClass.fields.mapNotNull inside@{
                    val intermediateFieldName = it.names[firstIntermediaryId]
                    val matchingField = fieldsByName[intermediateFieldName]
                        ?: if (requireMatch) error("No matching field found for ${it.names}!") else return@inside null

                    MappedField(
                        names = it.updateNames(intermediateFieldName, matchingField),
                        comments = it.comments + matchingField.comments,
                        desc = it.desc?.let(finalizeRemapper::mapDesc)
                    )
                },
                methods = originalClass.methods.filter { it.names.first() != "<clinit>" }.mapNotNull inside@{
                    val intermediateMethodName = it.names[firstIntermediaryId]
                    val matchingMethod = methodsBySig[intermediateMethodName + firstBaseRemapper.mapMethodDesc(it.desc)]
                        ?: (if (intermediateMethodName == "<init>") return@inside it.copy(names = initNames) else null)
                        ?: if (requireMatch) error("No matching method found for ${it.names}!") else return@inside null

                    MappedMethod(
                        names = it.updateNames(intermediateMethodName, matchingMethod),
                        comments = it.comments + matchingMethod.comments,
                        desc = finalizeRemapper.mapMethodDesc(it.desc),
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