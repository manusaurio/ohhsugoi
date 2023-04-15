package ar.pelotude.ohhsugoi.util.image

enum class DownloadErrorType {
    DIMENSIONS_EXCEEDED,
    UNSUPPORTED_FORMAT,
}

class UnsupportedDownloadException(
    message: String?,
    cause: Throwable? = null,
    code: DownloadErrorType,
) : Exception(message, cause)