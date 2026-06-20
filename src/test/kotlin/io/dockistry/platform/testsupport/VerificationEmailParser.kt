package io.dockistry.platform.testsupport

object VerificationEmailParser {
    private val numberCodeRegex = Regex(
        pattern = """Your verification code:\s*(\d{6})""",
        option = RegexOption.IGNORE_CASE
    )

    fun numberCode(email: SentEmail): String {
        return requireNotNull(numberCodeRegex.find(email.text)?.groupValues?.get(1)) {
            "Verification number code not found in email body"
        }
    }

    fun linkCode(email: SentEmail, linkPath: String): String {
        val linkCodeRegex = Regex(
            pattern = "${Regex.escape(linkPath)}/([A-Za-z0-9_-]+)"
        )
        return requireNotNull(linkCodeRegex.find(email.text)?.groupValues?.get(1)) {
            "Verification link code not found in email body"
        }
    }
}
