package com.grappenmaker.mappings

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
    minecraftMappingsStream("1.20")
}

private const val forgeMavenRoot = "https://maven.minecraftforge.net/de/oceanlabs/mcp"
// older versions
private const val mcpSnapshot = "$forgeMavenRoot/mcp_snapshot/maven-metadata.xml"
// newer versions
private const val mcpConfig = "$forgeMavenRoot/mcp_config/maven-metadata.xml"

data class MCPVersion(
    val gameVersion: String,
    val snapshot: String,
    val namespace: String
)

fun MCPVersion.downloadNames(to: Path) =
    DownloadUtil.download(
        URL("$forgeMavenRoot/mcp_$namespace/$snapshot-$gameVersion/mcp_$namespace-$snapshot-$gameVersion.zip"),
        to
    )

fun MCPVersion.downloadMappings(to: Path) {
    val url = if (namespace == "stable")
        URL("$forgeMavenRoot/mcp/$gameVersion/mcp-$gameVersion-srg.zip")
    else
        URL("$forgeMavenRoot/mcp_$namespace/$gameVersion/mcp_$namespace-$gameVersion.zip")

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

fun minecraftMappingsStream(version: String): InputStream {
    val versionInt = version.substringAfter("1.").toDouble()
    val isNew = versionInt >= 12.2

    println(isNew)

    val url = if (isNew) mcpConfig else mcpSnapshot
    val mcVersion = ForgeXMLParser.parseVersions(url)[version]
        ?: error("Could not find version $version in $url")

    val snapshot = mcVersion.snapshot

    val cachePath = Path("${System.getProperty("user.home")}/.weave/.cache/mappings/snapshot_$snapshot")
    val namesCache = cachePath.resolve("mcp-$snapshot-${version}-names.zip")
    val mappingsCache = cachePath.resolve("mcp-$snapshot-${version}-mappings.zip")
    val mergedMappingsCached = cachePath.resolve("mcp-$snapshot-${version}-merged.tiny")

    if (!mergedMappingsCached.exists()) {
        mcVersion.apply {
            if (!namesCache.exists() && !isNew) downloadNames(namesCache)
            if (!mappingsCache.exists()) downloadMappings(mappingsCache)
        }

        val mappingsContent = readFullZip(ZipFile(mappingsCache.toFile()))

        val mappings =
            if (isNew) mappingsContent.getValue("config/joined.tsrg")
            else mappingsContent.getValue("joined.srg")

        val originalMappings = MappingsLoader.loadMappings(mappings.decodeToString().nonBlankLines())

        val finalMappings = if (isNew)
            originalMappings
        else {
            val namesContent = readFullZip(ZipFile(namesCache.toFile()))

            originalMappings.mergeSRGWithMCP(
                methods = namesContent.getValue("methods.csv").decodeToString().nonBlankLines(),
                fields = namesContent.getValue("fields.csv").decodeToString().nonBlankLines(),
            )
        }

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
                MCPVersion(before, after, "config")
        }

        return versions.groupBy { it.gameVersion }
            .mapValues { it.value.maxByOrNull { version -> version.snapshot }!! }.values.toList()
            .associateBy { version -> version.gameVersion }
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