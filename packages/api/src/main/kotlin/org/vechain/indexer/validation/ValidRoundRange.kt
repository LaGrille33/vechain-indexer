package org.vechain.indexer.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass
import org.vechain.indexer.b3tr.action.RoundRange

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [ValidRoundRangeValidator::class])
@MustBeDocumented
annotation class ValidRoundRange(
    val message: String =
        "The provided round range is invalid. Allowed values: [\"3M\", \"6M\", \"1Y\", \"ALL\"]",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class ValidRoundRangeValidator : ConstraintValidator<ValidRoundRange, String> {
    override fun isValid(
        value: String,
        constraintValidatorContext: ConstraintValidatorContext,
    ): Boolean {
        return try {
            RoundRange.fromPathValue(value)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
