package es.personal.avisosairef.data.parser

sealed interface ParserResult {
    data class Success(val publications: List<Publicacion>, val snapshot: PageSnapshot) : ParserResult
    data class Failure(val reason: ParserFailureReason, val message: String) : ParserResult
}

enum class ParserFailureReason {
    SelectionMissing,
    EmptyUnexpected,
    ImplausibleResult
}
