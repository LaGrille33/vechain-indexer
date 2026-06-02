package org.vechain.indexer.docs

import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.core.annotation.AliasFor

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Parameter(
    name = "range",
    description = "Round range preset to use for the query.",
    schema = Schema(type = "string", allowableValues = ["3M", "6M", "1Y", "ALL"]),
    required = true,
)
annotation class RoundRangeParameter(
    @get:AliasFor(annotation = Parameter::class, attribute = "description")
    val description: String = "Round range preset to use for the query.",
    @get:AliasFor(annotation = Parameter::class, attribute = "example") val example: String = "3M",
)
