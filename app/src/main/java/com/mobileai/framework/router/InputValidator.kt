package com.mobileai.framework.router

/**
 * On-device input validation & anti-hallucination guardrails for MobileAIFramework / ShopFloor AI.
 * Rejects gibberish, keyboard-mash, empty, or non-dimensional scans locally (<1ms) before
 * wasting on-device CPU/RAM, cloud Gemini tokens, or polluting SQLite / BigQuery.
 */
object InputValidator {

    data class ValidationResult(val isValid: Boolean, val reason: String = "")

    private val vowels = setOf('a', 'e', 'i', 'o', 'u', 'y')
    private val knownAcronyms = setOf(
        "cnc", "ocr", "rag", "fpy", "cmm", "vmc", "prj", "ins", "lmk", "npu",
        "ram", "sql", "api", "sdk", "ota", "gcs", "gpu", "cpu", "llm", "cpk",
        "cad", "cam", "pdf", "sop", "kv", "ai", "qc", "ws", "id", "od", "mm", "hi", "ok"
    )
    private val mashSubstrings = listOf(
        "asdf", "qwer", "zxcv", "hjkl", "uiop", "ghjk", "dfgh", "fghj",
        "sdfg", "xcvb", "cvbn", "vbnm", "wert", "erty", "rtyu", "tyui", "yuio"
    )
    private val qcDomainKeywords = listOf(
        "mm", "um", "inch", "nominal", "nom", "measured", "actual", "reading",
        "tolerance", "tol", "±", "+/-", "bore", "diameter", "od", "id", "radius",
        "thickness", "concentricity", "roughness", "ra", "shaft", "valve", "flange",
        "gear", "housing", "part", "prj-", "cnc-", "ins-", "machine", "lathe", "vmc",
        "inspection", "dimension", "spec", "pass", "fail", "warn", "yield", "fpy",
        "cpk", "scrap", "reject", "drift", "rework", "shift", "project", "batch",
        "caliper", "micrometer", "cmm", "blueprint", "drawing", "torque", "gauge", "deviation"
    )

    private val repeatCharRegex = Regex("(.)\\1{3,}")
    private val consonantRunRegex = Regex("[bcdfghjklmnpqrstvwxz]{5,}")
    private val numberRegex = Regex("\\b\\d+(?:\\.\\d+)?\\b")
    private val tokenRegex = Regex("[a-zA-Z0-9_-]+")

    private fun isGibberishWord(word: String): Boolean {
        val letters = word.filter { it.isLetter() }.lowercase()
        if (letters.length < 3) return false
        if (letters in knownAcronyms) return false
        if (mashSubstrings.any { letters.contains(it) }) return true
        if (repeatCharRegex.containsMatchIn(letters)) return true
        if (consonantRunRegex.containsMatchIn(letters)) return true
        if (letters.length >= 5 && letters.none { it in vowels }) return true
        return false
    }

    fun validateGeneralPrompt(text: String): ValidationResult {
        val s = text.trim()
        if (s.length < 2) {
            return ValidationResult(false, "Input is empty or too short. Please enter a real prompt.")
        }
        val letterCount = s.count { it.isLetter() }
        if (letterCount < 2) {
            return ValidationResult(false, "Input contains no recognizable text.")
        }
        val compact = s.filter { !it.isWhitespace() }.lowercase()
        if (compact.length >= 4 && compact.toSet().size <= 2) {
            return ValidationResult(false, "Input looks like repeated characters rather than a valid request.")
        }
        val alphaTokens = tokenRegex.findAll(s)
            .map { it.value }
            .filter { tok -> tok.count { it.isLetter() } >= 3 }
            .toList()
        if (alphaTokens.isNotEmpty()) {
            val gibCount = alphaTokens.count { isGibberishWord(it) }
            if (gibCount.toDouble() / alphaTokens.size >= 0.5) {
                return ValidationResult(
                    false,
                    "Unrecognized or nonsensical input ('${s.take(32)}'). Please enter a clear, valid prompt."
                )
            }
        }
        return ValidationResult(true)
    }

    fun validateQcScanText(ocrText: String): ValidationResult {
        val base = validateGeneralPrompt(ocrText)
        if (!base.isValid) {
            return ValidationResult(false, "Invalid scan: ${base.reason}")
        }
        val low = ocrText.lowercase()
        val hasNumbers = numberRegex.containsMatchIn(ocrText)
        if (!hasNumbers) {
            return ValidationResult(
                false,
                "No dimensional measurement numbers found. Scan a part tag or report with Nominal, Tolerance, and Measured mm."
            )
        }
        val hasDomainCue = qcDomainKeywords.any { low.contains(it) }
        if (!hasDomainCue) {
            return ValidationResult(
                false,
                "Scanned text does not look like a machining or quality inspection report (missing Nominal/Measured/mm/Bore/PRJ-/CNC-)."
            )
        }
        return ValidationResult(true)
    }
}
