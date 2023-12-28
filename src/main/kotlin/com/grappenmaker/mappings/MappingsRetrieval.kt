package com.grappenmaker.mappings

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.zip.ZipFile
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.writeLines

fun main() {
    mcpMappingsStream("1.15.1")
}

private const val forgeMavenRoot = "https://maven.minecraftforge.net/de/oceanlabs/mcp"

data class MCPVersion(
    val version: String,
    val snapshot: String,
    val channel: String
)

fun MCPVersion.downloadNames(to: Path) =
    DownloadUtil.download(
        URL("$forgeMavenRoot/mcp_snapshot/$snapshot-$version/mcp_snapshot-$snapshot-$version.zip"),
        to
    )

fun MCPVersion.downloadMappings(to: Path) {
    val url = if (channel == "stable")
        URL("$forgeMavenRoot/mcp/$version/mcp-$version-srg.zip")
    else
        URL("$forgeMavenRoot/mcp_$channel/$version/mcp_$channel-$version.zip")

    DownloadUtil.download(url, to)
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

    val snapshot = mcVersion.snapshot

    val cachePath = Path("${System.getProperty("user.home")}/.weave/.cache/mappings/mcp_$version")
    val namesCache = cachePath.resolve("names.zip")
    val mappingsCache = cachePath.resolve("mappings.zip")
    val mergedMappingsCached = cachePath.resolve("merged.tiny")

    if (!mergedMappingsCached.exists()) {
        mcVersion.apply {
            if (!namesCache.exists()) downloadNames(namesCache)
            if (!mappingsCache.exists()) downloadMappings(mappingsCache)
        }

        val mappingsContent = readFullZip(ZipFile(mappingsCache.toFile()))
        val namesContent = readFullZip(ZipFile(namesCache.toFile()))
        val joinedContent = mappingsContent.getValue(joinedMappings)

        val originalMappings = MappingsLoader.loadMappings(joinedContent.decodeToString().nonBlankLines())
        val finalMappings = originalMappings.mergeSRGWithMCP(
            methods = namesContent.getValue("methods.csv").decodeToString().nonBlankLines(),
            fields = namesContent.getValue("fields.csv").decodeToString().nonBlankLines(),
        )

        mergedMappingsCached.writeLines(finalMappings.asTinyMappings(v2 = true).write())
    }

    return mergedMappingsCached.inputStream()
}

fun String.nonBlankLines() = this.lines().filter { it.isNotBlank() }

fun readFullZip(zipFile: ZipFile): Map<String, ByteArray> =
    zipFile.use { o ->
        zipFile.entries().asSequence().associate {
            it.name to o.getInputStream(it).readBytes()
        }
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

object DownloadUtil {
    /**
     * Returns the SHA1 checksum of the file as a [String]
     *
     * @param file The file to check.
     * @return the SHA1 checksum of the file.
     */
    private fun checksum(file: Path) = try {
        if (!file.exists()) null
        else {
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buffer = ByteArray(0x2000)
                var read: Int

                while (input.read(buffer).also { read = it } >= 0) {
                    digest.update(buffer, 0, read)
                }
            }

            digest.digest().joinToString { "%02x".format(it) }
        }
    } catch (ex: IOException) {
        ex.printStackTrace()
        null
    } catch (ignored: NoSuchAlgorithmException) {
        null
    }

    /**
     * Downloads a file from any URL
     *
     * @param url The URL to download from.
     * @param path The path to download to.
     */
    fun download(url: URL, path: Path) {
        println(url)
        runCatching {
            url.openStream().use { input ->
                Files.createDirectories(path.parent)
                Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { it.printStackTrace() }
    }

    fun download(url: String, path: String) = download(URL(url), Paths.get(path))

    /**
     * Fetches data from any URL
     *
     * @param url The URL to download from
     */
    private fun fetch(url: URL) = runCatching { url.openStream().readBytes().decodeToString() }
        .onFailure { it.printStackTrace() }.getOrNull()

    fun fetch(url: String) = fetch(URL(url))

    /**
     * Downloads and checksums a file.
     *
     * @param url The URL to download from.
     * @param checksum The checksum to compare to.
     * @param path The path to download to.
     */
    fun checksumAndDownload(url: URL, checksum: String, path: Path) {
        if (checksum(path) != checksum) download(url, path)
    }
}