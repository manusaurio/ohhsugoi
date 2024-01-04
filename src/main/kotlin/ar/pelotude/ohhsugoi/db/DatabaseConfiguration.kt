package ar.pelotude.ohhsugoi.db

import io.ktor.http.*
import java.nio.file.Path

class DatabaseConfiguration(
        val mangaCoversWidth: Int,
        val mangaCoversHeight: Int,
        val webpage: Url,
        val mangaImageDirectory: Path,
        val mangaCoversUrlPath: String,
        val sqlitePath: String,
        val pollImagesDirectory: Path,
        val pollImagesUrlPath: String,
)