package com.arenacity.presentation.route

import com.arenacity.common.response.toSuccessResponse
import com.arenacity.core.plugins.AppJson
import com.arenacity.presentation.dto.MangaCatalogDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

/**
 * The «ARENA: The Great Games» manga read in the game (Team → «Манга»). Public, no auth.
 * [dir] (config manga.dir, env MANGA_DIR) holds catalog.json and epNN/NN.jpg, written by the mobile repo's tools/publish_manga.py.
 * GET /api/v1/manga → the catalog (empty when the folder or catalog.json is missing);
 * GET /manga/{ep}/{file} → a page JPEG; strict names, so nothing outside [dir] can be read.
 */
fun Route.mangaCatalogRoutes(dir: File) {
    get("/manga") { call.respond(readCatalog(dir).toSuccessResponse()) }
}

fun Route.mangaFileRoutes(dir: File) {
    get("/manga/{ep}/{file}") {
        val ep = call.parameters["ep"].orEmpty()
        val file = call.parameters["file"].orEmpty()
        if (!EP.matches(ep) || !PAGE.matches(file)) return@get call.respond(HttpStatusCode.NotFound)
        val f = File(File(dir, ep), file)
        if (!f.isFile) return@get call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
        call.respondFile(f)
    }
}

fun Application.mangaDir(): File = File(environment.config.propertyOrNull("manga.dir")?.getString()?.trim()?.ifEmpty { null } ?: "manga")

private val EP = Regex("ep\\d{2}")
private val PAGE = Regex("\\d{2,3}\\.jpg")

/** catalog.json re-read only when the file changes, so a publish needs no restart. */
private object CatalogCache {
    @Volatile var stamp: Pair<String, Long>? = null
    @Volatile var value: MangaCatalogDto = MangaCatalogDto()
}

internal fun readCatalog(dir: File): MangaCatalogDto {
    val f = File(dir, "catalog.json")
    if (!f.isFile) return MangaCatalogDto()
    val stamp = f.absolutePath to f.lastModified()
    if (CatalogCache.stamp == stamp) return CatalogCache.value
    val parsed = runCatching { AppJson.decodeFromString(MangaCatalogDto.serializer(), f.readText()) }.getOrElse { MangaCatalogDto() }
    CatalogCache.value = parsed; CatalogCache.stamp = stamp
    return parsed
}
