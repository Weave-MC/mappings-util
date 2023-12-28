package com.grappenmaker.mappings

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.writeLines

fun main() {
    mcpMappingsStream("1.8.9")
}

private const val forgeMavenRoot = "https://maven.minecraftforge.net/de/oceanlabs/mcp"

data class MCPVersion(
    val version: String,
    val snapshot: String,
    val channel: String
)

fun MCPVersion.mcpMappingsStream(): ZipInputStream = ZipInputStream(
    URL("$forgeMavenRoot/mcp_snapshot/$snapshot-$version/mcp_snapshot-$snapshot-$version.zip").openStream()
)
fun MCPVersion.srgMappingsStream(useNew: Boolean): ZipInputStream {
    return if (useNew)
        ZipInputStream(URL("$forgeMavenRoot/mcp_$channel/$version/mcp_$channel-$version.zip").openStream())
    else
        ZipInputStream(URL("$forgeMavenRoot/mcp/$version/mcp-$version-srg.zip").openStream())
}

private fun List<String>.asNamesMapping(): Map<String, String> {
    val meaning = first()
    val meaningIndices = meaning.split(',')

    val fromIdx = meaningIndices.indexOf("searge")
    val toIdx = meaningIndices.indexOf("name")

    return drop(1).map { it.split(',') }.associate { it[fromIdx] to it[toIdx] }
}

fun Mappings.mergeSRGWithMCP(methods: List<String>, fields: List<String>): GenericMappings {
    val methodsMapping = methods.asNamesMapping()
    val fieldsMapping = fields.asNamesMapping()

    return GenericMappings(
        namespaces = namespaces + "named",
        classes = classes.map { oc ->
            oc.copy(
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

fun mcpMappingsStream(version: String): InputStream {
    val versionInt = version.substringAfter("1.").toDouble()
    val joinedMappings = if (versionInt >= 13) "config/joined.tsrg" else "joined.srg"
    val mappingsChannel = if (versionInt >= 15.1) "config" else "snapshot"

    if (versionInt >= 16)
        error("Versions 1.16+ do not have MCP mappings associated")

    val url = "$forgeMavenRoot/mcp_$mappingsChannel/maven-metadata.xml"
    val mcVersion = ForgeXMLParser.parseVersions(url)[version]
        ?: error("Could not find version $version in $url")

    val cachePath = Path("${System.getProperty("user.home")}/.weave/.cache/mappings/mcp_$version")
    val mergedMappingsCache = cachePath.resolve("merged.tiny")

    if (!mergedMappingsCache.exists()) {
        mergedMappingsCache.parent.toFile().mkdirs()

        val srgMappingsContent = readZipStream(mcVersion.srgMappingsStream(versionInt >= 13))
        val mcpMappingsContent = readZipStream(mcVersion.mcpMappingsStream())

        val joinedMappings = srgMappingsContent[joinedMappings]
            ?: error("Failed to find $joinedMappings in SRG mappings zip")

        val originalMappings = MappingsLoader.loadMappings(joinedMappings.decodeToString().nonBlankLines())
        val finalMappings = originalMappings.mergeSRGWithMCP(
            methods = mcpMappingsContent["methods.csv"]?.decodeToString()?.nonBlankLines()
                ?: error("Failed to find methods.csv in MCP mappings zip"),
            fields = mcpMappingsContent["fields.csv"]?.decodeToString()?.nonBlankLines()
                ?: error("Failed to find fields.csv in MCP mappings zip"),
        )

        mergedMappingsCache.writeLines(finalMappings.asTinyMappings(v2 = true).write())
    }

    return mergedMappingsCache.inputStream()
}

fun String.nonBlankLines() = this.lines().filter { it.isNotBlank() }

fun readZipStream(zip: ZipInputStream): Map<String, ByteArray> {
    val entries = mutableMapOf<String, ByteArray>()
    var entry = zip.nextEntry

    val buffer = ByteArray(1024)
    while (entry != null) {
        val baos = ByteArrayOutputStream()

        var bytesRead: Int
        while (zip.read(buffer).also { bytesRead = it } != -1) {
            baos.write(buffer, 0, bytesRead)
        }

        entries[entry.name] = baos.toByteArray()
        entry = zip.nextEntry
    }

    return entries
}

object ForgeXMLParser {
    private val versionRegex = Regex("<version>(.*?)</version>")

    fun parseVersions(url: String): Map<String, MCPVersion> {
        val text = URL(url).readText()
        val versionsString = versionRegex.findAll(text).map { it.groupValues[1] }.toList()
        val versions: List<MCPVersion> = versionsString.map {
            val (before, after) = it.splitAround('-')
            if (url.contains("snapshot"))
                MCPVersion(after, before, "snapshot")
            else
                MCPVersion(before, after.substringBefore('.'), "config")
        }

        return versions.groupBy { it.version }
            .mapValues { it.value.maxByOrNull { version -> version.snapshot }!! }.values.toList()
            .associateBy { version -> version.version }
    }
}