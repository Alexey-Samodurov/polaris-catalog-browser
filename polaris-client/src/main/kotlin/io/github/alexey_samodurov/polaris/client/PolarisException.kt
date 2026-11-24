package io.github.alexey_samodurov.polaris.client

/**
 * Every failure the client reports.
 *
 * Messages carry the method, the path and the server's own error message. They never carry a
 * request body, a header or a token, so the secret cannot reach a log through an exception.
 */
sealed class PolarisException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 401, or a token request the server refused. */
    class Unauthorized(message: String) : PolarisException(message)

    /** 403: the principal lacks the privilege. */
    class Forbidden(message: String) : PolarisException(message)

    /** 404: the catalog, namespace, table or view does not exist (any more). */
    class NotFound(message: String) : PolarisException(message)

    /** Any other non-success status. */
    class Server(val status: Int, message: String) : PolarisException(message)

    /** The server could not be reached, or did not answer in time. */
    class Transport(message: String, cause: Throwable?) : PolarisException(message, cause)

    /** The server answered with a body the client cannot read at all. */
    class BadResponse(message: String, cause: Throwable? = null) : PolarisException(message, cause)
}
