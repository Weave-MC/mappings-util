@file:OptIn(ExperimentalTypeInference::class)

package com.grappenmaker.mappings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.io.InputStream
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipInputStream
import kotlin.experimental.ExperimentalTypeInference
import kotlin.io.path.*

private const val forgeMavenRoot = "https://maven.minecraftforge.net/de/oceanlabs/mcp"
private const val yarnMavenRoot = "https://maven.fabricmc.net/net/fabricmc/yarn"

data class MCPVersion(
    val version: String,
    val snapshot: String?,
    val fullVersion: String,
    val channel: String
)

fun MCPVersion.mcpMappingsStream(): ZipInputStream = ZipInputStream(
    URL("$forgeMavenRoot/mcp_$channel/$fullVersion/mcp_$channel-$fullVersion.zip").openStream()
)

fun MCPVersion.srgMappingsStream(useNew: Boolean): ZipInputStream =
    if (useNew) ZipInputStream(URL("$forgeMavenRoot/mcp_config/$version/mcp_config-$version.zip").openStream())
    else ZipInputStream(URL("$forgeMavenRoot/mcp/$version/mcp-$version-srg.zip").openStream())

private fun List<String>.asNamesMapping(): Map<String, String> {
    val meaning = first().split(',')
    val fromIdx = meaning.indexOf("searge")
    val toIdx = meaning.indexOf("name")

    return drop(1).map { it.split(',') }.associate { it[fromIdx] to it[toIdx] }
}

fun Mappings.fixSRGNamespaces(): Mappings = if (namespaces.size == 2) renameNamespaces("obf", "srg") else this

fun Mappings.mergeSRGWithMCP(methods: List<String>, fields: List<String>): GenericMappings {
    require("named" !in namespaces)

    val methodsMapping = methods.asNamesMapping()
    val fieldsMapping = fields.asNamesMapping()

    return GenericMappings(
        namespaces = namespaces + "named",
        classes = classes.map { oc ->
            oc.copy(
                names = oc.names + oc.names.last(),
                methods = oc.methods.map {
                    val last = it.names.last()
                    it.copy(names = it.names + (methodsMapping[last] ?: last))
                },
                fields = oc.fields.map {
                    val last = it.names.last()
                    it.copy(names = it.names + (fieldsMapping[last] ?: last))
                },
            )
        }
    )
}

fun mcpMappingsStream(version: String, gameJar: File): InputStream? {
    val versionDecimal = version.substringAfter("1.").toDouble()
    val joinedMappingsPath = if (versionDecimal >= 13) "config/joined.tsrg" else "joined.srg"
    val mappingsChannel = if (versionDecimal >= 15.1) "config" else "snapshot"
    if (versionDecimal >= 16) return null

    return mappingsCache("mcp", version).getOrPut {
        val url = "$forgeMavenRoot/mcp_$mappingsChannel/maven-metadata.xml"
        val mcVersion = parseMCPVersions(url)[version] ?: error("Could not find version $version in $url")
        val srgMappingsContent = mcVersion.srgMappingsStream(versionDecimal >= 13).readEntries()
        val mcpMappingsContent = mcVersion.mcpMappingsStream().readEntries()

        val joinedMappings = srgMappingsContent[joinedMappingsPath]
            ?: error("Failed to find $joinedMappingsPath in SRG mappings zip")

        val originalMappings = JarFile(gameJar).use { jar ->
            MappingsLoader.loadMappings(joinedMappings.decodeToString().nonBlankLines())
                .fixSRGNamespaces()
                .removeRedundancy(jar)
                .recoverFieldDescs(jar)
        }

        val finalMappings = originalMappings.mergeSRGWithMCP(
            methods = mcpMappingsContent["methods.csv"]?.decodeToString()?.nonBlankLines()
                ?: error("Failed to find methods.csv in MCP mappings zip"),
            fields = mcpMappingsContent["fields.csv"]?.decodeToString()?.nonBlankLines()
                ?: error("Failed to find fields.csv in MCP mappings zip"),
        )

        finalMappings.renameNamespaces("official", "srg", "named").asTinyMappings(v2 = true).write()
    }
}

fun String.nonBlankLines() = lines().filter { it.isNotBlank() }
fun ZipInputStream.readEntries() = generateSequence { nextEntry }.associate { it.name to readBytes() }

private val versionRegex = """<version>(.*?)</version>""".toRegex()
fun String.parseXMLVersions() = versionRegex.findAll(this).map { it.groupValues[1] }.toList()

fun parseMCPVersions(url: String): Map<String, MCPVersion> {
    val text = URL(url).readText()
    val versionsString = text.parseXMLVersions()
    val versions: List<MCPVersion> = versionsString.map { l ->
        val (before, after) = l.splitAround('-')
        when {
            "snapshot" in url -> MCPVersion(after, before, l, "snapshot")
            else -> MCPVersion(before, after.substringBefore('.').takeIf { it.isNotEmpty() }, l, "config")
        }
    }

    return versions.groupBy { it.version }.mapValues { (_, v) ->
        v.find { it.snapshot == null } ?: v.maxBy { it.snapshot!! }
    }
}

fun yarnMappingsStream(version: String, gameJar: File): InputStream? {
    return mappingsCache("yarn", version).getOrPut {
        val versions = URL("$yarnMavenRoot/maven-metadata.xml").readText().parseXMLVersions()
        val targetVersion = versions
            .filter { it.substringBefore('+') == version }
            .maxByOrNull { it.substringAfterLast('.').toInt() }
            ?: return null

        val versionEncoded = URLEncoder.encode(targetVersion, "UTF-8")
        val url = "$yarnMavenRoot/$versionEncoded/yarn-$versionEncoded.jar"
        val pathInJar = "mappings/mappings.tiny"
        val entries = ZipInputStream(URL(url).openStream()).readEntries()

        JarFile(gameJar).use { jar ->
            MappingsLoader.loadMappings(entries.getValue(pathInJar).decodeToString().lines())
                .removeRedundancy(jar)
                .asTinyMappings(v2 = true)
                .write()
        }
    }
}

fun mappingsCache(id: String, version: String) =
    Path(System.getProperty("user.home"), ".weave", ".cache", "mappings", "${id}_$version", "mappings.tiny")

@OverloadResolutionByLambdaReturnType
fun Mappings.recoverFieldDescs(bytesProvider: (name: String) -> ByteArray?): Mappings =
    recoverFieldDescs { name -> bytesProvider(name)?.let { b -> ClassNode().also { ClassReader(b).accept(it, 0) } } }

@JvmName("recoverDescsByNode")
@OverloadResolutionByLambdaReturnType
fun Mappings.recoverFieldDescs(nodeProvider: (name: String) -> ClassNode?): Mappings = GenericMappings(
    namespaces,
    classes.map { oc ->
        val node by lazy { nodeProvider(oc.names.first()) }
        val fieldsByName by lazy { node?.fields?.associateBy { it.name } ?: emptyMap() }

        oc.copy(fields = oc.fields.mapNotNull { of ->
            of.copy(desc = of.desc ?: (fieldsByName[of.names.first()]?.desc ?: return@mapNotNull null))
        })
    }
)

fun Mappings.recoverFieldDescs(file: JarFile) = recoverFieldDescs a@{
    file.getInputStream(file.getJarEntry("$it.class") ?: return@a null).readBytes()
}

fun MappedMethod.isData() = desc == "(Ljava/lang/Object;)Z" && names.first() == "equals" ||
        desc == "()I" && names.first() == "hashCode" ||
        desc == "()Ljava/lang/String;" && names.first() == "toString" ||
        names.first() == "<init>" || names.first() == "<clinit>"

fun Mappings.removeRedundancy(bytesProvider: (name: String) -> ByteArray?): Mappings = GenericMappings(
    namespaces,
    classes.map { oc ->
        val name = oc.names.first()
        val ourSigs = hashSetOf<String>()
        val superSigs = hashSetOf<String>()

        walkInheritance(bytesProvider, name) { curr ->
            val target = if (curr == name) ourSigs else superSigs
            bytesProvider(curr)?.let { b -> ClassNode().also { ClassReader(b).accept(it, 0) } }
                ?.methods?.forEach { m -> target += "${m.name}${m.desc}" }

            false
        }

        oc.copy(methods = oc.methods.filter {
            val sig = "${it.names.first()}${it.desc}"
            sig in ourSigs && sig !in superSigs && !it.isData()
        })
    }
)

fun Mappings.removeRedundancy(file: JarFile): Mappings {
    val cache = hashMapOf<String, ByteArray?>()

    return removeRedundancy a@{
        cache.getOrPut(it) { file.getInputStream(file.getJarEntry("$it.class") ?: return@a null).readBytes() }
    }
}

inline fun Path.getOrPut(block: () -> Iterable<CharSequence>): InputStream {
    createParentDirectories()

    if (!exists()) writeLines(block())
    require(exists())

    return inputStream()
}

internal val json = Json { ignoreUnknownKeys = true }
internal inline fun <reified T : Any> String.decodeJSON() = json.decodeFromString<T>(this)

private fun mojangMappingsStream(version: String, gameJar: File): InputStream? {
    return mappingsCache("mojmap", version).getOrPut {
        val manifest = URL("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json")
            .readText().decodeJSON<VersionManifest>()

        val versionEntry = manifest.versions.find { it.id == version } ?: return null
        val versionInfo = URL(versionEntry.url).readText().decodeJSON<VersionInfo>()
        val mappings = versionInfo.downloads.mappings ?: return null
        require(mappings.size != -1) { "Invalid mappings entry for version $version: $mappings" }

        JarFile(gameJar).use { jar ->
            MappingsLoader.loadMappings(URL(mappings.url).readText().lines())
                .filterClasses { it.names.first().substringAfterLast('/') != "package-info" }
                .reorderNamespaces("official", "named")
                .removeRedundancy(jar)
                .asTinyMappings(v2 = true).write()
        }
    }
}

@Serializable
private data class VersionManifest(val versions: List<ManifestVersion>)

@Serializable
private data class ManifestVersion(val id: String, val url: String)

@Serializable
private data class VersionInfo(val downloads: VersionDownloads)

@Serializable
private data class VersionDownloads(
    val client: VersionDownload,
    @SerialName("client_mappings") val mappings: ClientMappings? = null
)

@Serializable
private data class ClientMappings(
    val url: String,
    val sha1: String,
    val size: Int = -1
)

@Serializable
private data class VersionDownload(val url: String, val sha1: String)

private fun allMappings(version: String, gameJar: File) =
    listOf("mcp", "yarn", "mojmap").mapNotNull { loadWeaveMappings(it, version, gameJar) }

private fun mergedMappingsStream(version: String, gameJar: File): InputStream =
    mappingsCache("merged", version).getOrPut {
        val joined = allMappings(version, gameJar).map { (id, mappings) ->
            mappings.renameNamespaces(mappings.namespaces.map { if (it == "official") it else "$id-$it" })
        }.join("official")

        val otherNs = joined.namespaces - "official"
        joined.reorderNamespaces(listOf("official") + otherNs).asTinyMappings(v2 = true).write()
    }

data class WeaveMappings(val id: String, val mappings: Mappings)

fun loadWeaveMappings(id: String, version: String, gameJar: File) = when (id) {
    "yarn" -> yarnMappingsStream(version, gameJar)
    "mcp" -> mcpMappingsStream(version, gameJar)
    "mojmap" -> mojangMappingsStream(version, gameJar)
    "merged" -> mergedMappingsStream(version, gameJar)
    else -> error("Unknown weave mappings id $version")
}?.let { WeaveMappings(id, MappingsLoader.loadMappings(it.readBytes().decodeToString().lines())) }

fun loadMergedWeaveMappings(version: String, gameJar: File) = loadWeaveMappings("merged", version, gameJar)!!