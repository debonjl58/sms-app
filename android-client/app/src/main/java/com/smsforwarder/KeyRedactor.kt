package com.smsforwarder

/**
 * Masque les données sensibles avant écriture disque et transport.
 */
object KeyRedactor {

    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]{2,}")
    private val IBAN = Regex("\\b[A-Z]{2}\\d{2}[A-Z0-9]{10,30}\\b")
    private val CARD = Regex("\\b(?:\\d[ -]?){13,19}\\b")
        private val PHONE = Regex("\\+?\\d[\\d .-]{8,}\\d")

        fun redact(input: String): String {
            if (!Config.REDACT || input.isEmpty()) return input

                var output = EMAIL.replace(input) { m ->
                    "[MAIL:${m.value.take(1)}***]"
                }
                output = IBAN.replace(output) { m -> "[IBAN:${m.value.takeLast(4)}]" }
                output = CARD.replace(output) { m ->
                    val digits = m.value.filter { it.isDigit() }
                    if (digits.length in 13..19 && luhn(digits)) "[CARTE:****${digits.takeLast(4)}]" else m.value
                }
                output = PHONE.replace(output) { m ->
                    val digits = m.value.filter { it.isDigit() }
                    if (digits.length >= 9) "[TEL:****${digits.takeLast(2)}]" else m.value
                }
                return output
        }

        private fun luhn(digits: String): Boolean {
            var sum = 0
            var double = false
            for (index in digits.indices.reversed()) {
                var value = digits[index] - '0'
                if (double) {
                    value *= 2
                    if (value > 9) value -= 9
                }
                sum += value
                double = !double
            }
            return sum % 10 == 0
        }
}
