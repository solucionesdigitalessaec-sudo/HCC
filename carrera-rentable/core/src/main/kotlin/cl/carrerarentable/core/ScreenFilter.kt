package cl.carrerarentable.core

/** Una línea reconocida en la captura de pantalla, con su posición vertical (px). */
data class ScreenLine(val text: String, val top: Int, val bottom: Int)

/** Limpieza del texto reconocido por imagen antes de buscar una solicitud. */
object ScreenFilter {

    private val PANEL_TOP = Regex("""\bRENTABLE\b""", RegexOption.IGNORE_CASE)
    private val PANEL_BOTTOM = Regex("""toca para cerrar|arrastra para mover""", RegexOption.IGNORE_CASE)
    /** Textos que solo existen en nuestro panel. Si quedan después de filtrar, la captura no sirve. */
    private val PANEL_ANY = Regex(
        """\bRENTABLE\b|KM A MANEJAR|POR HORA|toca para cerrar|arrastra para mover|para llegar a|m[ií]nimo \$""",
        RegexOption.IGNORE_CASE,
    )

    /** Botón o aviso para aceptar: solo hay una solicitud real si aparece en pantalla. */
    private val ACCEPT = Regex("""\bacept(ar|a)\b|toca para aceptar|desliza para aceptar|\baccept\b""", RegexOption.IGNORE_CASE)

    /**
     * Quita nuestro propio panel de la captura (si quedó en la imagen) para no leer sus números.
     * Devuelve null si se ve parte del panel pero no se puede delimitar: esa captura se descarta.
     */
    fun removeOwnPanel(lines: List<ScreenLine>): List<ScreenLine>? {
        val top = lines.firstOrNull { PANEL_TOP.containsMatchIn(it.text) }
        val bottom = lines.lastOrNull { PANEL_BOTTOM.containsMatchIn(it.text) }
        val cleaned = if (top != null && bottom != null && bottom.bottom >= top.top) {
            lines.filter { it.bottom < top.top - 4 || it.top > bottom.bottom + 4 }
        } else lines
        return if (cleaned.any { PANEL_ANY.containsMatchIn(it.text) }) null else cleaned
    }

    fun hasAcceptButton(lines: List<String>): Boolean = lines.any { ACCEPT.containsMatchIn(it) }
}
