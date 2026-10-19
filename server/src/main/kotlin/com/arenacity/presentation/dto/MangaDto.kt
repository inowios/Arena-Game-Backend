package com.arenacity.presentation.dto

import kotlinx.serialization.Serializable

/** A manga page: number [n] and a short hash [v] of its JPEG; the client caches by it and re-downloads when it changes. */
@Serializable data class MangaPageDto(val n: Int, val v: String)
/** An episode: [id] epNN (the folder of its pages, NN.jpg), [title] in English like the text on the pages, [lang] of that text. */
@Serializable data class MangaEpisodeDto(val id: String, val number: Int, val title: String, val lang: String = "en", val pages: List<MangaPageDto> = emptyList())
/** GET /api/v1/manga. */
@Serializable data class MangaCatalogDto(val episodes: List<MangaEpisodeDto> = emptyList())
