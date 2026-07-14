package org.lsposed.lspatch.util

object PackageNameValidator {

    private val SEGMENT = Regex("^[a-zA-Z][a-zA-Z0-9_]*$")

    fun isValid(packageName: String): Boolean {
        if (packageName.isBlank() || packageName.length > 255) return false
        val parts = packageName.split('.')
        if (parts.size < 2) return false
        return parts.all { SEGMENT.matches(it) }
    }

    fun randomPackageName(): String {
        val alphabet = ('a'..'z')
        fun word(len: Int) = (1..len).map { alphabet.random() }.joinToString("")
        return "org.${word(8)}.${word(6)}"
    }
}
