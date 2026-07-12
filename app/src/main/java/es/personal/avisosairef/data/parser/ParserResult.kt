package es.personal.avisosairef.data.parser

sealed interface ParserResult {
    data class Success(val publications: List<Publicacion>) : ParserResult
    data class Failure(val reason: ParserFailureReason, val message: String) : ParserResult
}

enum class ParserFailureReason {
    TargetSectionMissing,
    EmptyUnexpected,
    ImplausibleResult
}
