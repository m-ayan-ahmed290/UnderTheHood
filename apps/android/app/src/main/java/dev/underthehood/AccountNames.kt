package dev.underthehood

object AccountNames {
    fun normalize(value:String):String {
        val name=value.trim()
        require(name.isNotEmpty() && name.codePointCount(0,name.length)<=64 && !name.startsWith('-') && name.codePoints().allMatch { c -> Character.isLetterOrDigit(c) || (c<=127 && c.toChar() in " .@+_-") }) {
            "Use 1–64 letters, numbers, spaces, dots, @, +, underscores or hyphens. A name cannot start with a hyphen."
        }
        return name
    }
}
object AuthorizationLinks {
    fun find(line:String):String? = line.split(Regex("\\s+")).firstNotNullOfOrNull { word ->
        val value=word.trim('"','\'')
        runCatching { java.net.URI(value) }.getOrNull()?.takeIf { uri ->
            uri.scheme=="http" && uri.host in listOf("127.0.0.1","localhost") && uri.port in 1..65535 && uri.path=="/auth" && uri.rawQuery.orEmpty().split('&').any { it.substringBefore('=')=="state" }
        }?.toString()
    }
}
