package ar.pelotude.ohhsugoi.db.scheduler

open class SerializerException(message: String, cause: Throwable? = null) : Exception(message, cause)

open class SerializerFatalException(message: String, cause: Throwable? = null): RuntimeException(message, cause)