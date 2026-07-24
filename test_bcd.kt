import java.util.Locale

fun encodeFrequencyBcd(frequencyHz: Long): ByteArray {
    val digits = String.format(Locale.US, "%010d", frequencyHz)
    val bcd = ByteArray(5)
    for (i in 0 until 5) {
        val idx = 10 - (i + 1) * 2
        val low = digits[idx] - '0'
        val high = digits[idx + 1] - '0'
        bcd[i] = ((high shl 4) or low).toByte()
    }
    return bcd
}

fun main() {
    val testFreqs = listOf(145800000L, 145970000L, 435100000L, 436400000L)
    
    for (freq in testFreqs) {
        val bcd = encodeFrequencyBcd(freq)
        val digits = String.format(Locale.US, "%010d", freq)
        println("Frequency: $freq")
        println("Digits: $digits")
        println("BCD: ${bcd.joinToString(" ") { "%02X".format(it) }}")
        
        // Show the encoding process
        for (i in 0 until 5) {
            val idx = 10 - (i + 1) * 2
            val low = digits[idx] - '0'
            val high = digits[idx + 1] - '0'
            val byte = ((high shl 4) or low).toByte()
            println("  i=$i: idx=$idx, digits[$idx]='${digits[idx]}', digits[${idx+1}]='${digits[idx+1]}', low=$low, high=$high, byte=0x${"%02X".format(byte)}")
        }
        println()
    }
}
