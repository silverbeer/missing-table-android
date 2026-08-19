package com.missingtable.scorer.domain

/**
 * Parses a typed list of shirt numbers into lineup slots (SB-787).
 *
 * The opposition rarely has a roster, so their lineup has to be entered from
 * a team sheet at kickoff. Doing that one slot at a time is ~44 interactions
 * on a pitch diagram; this reads the numbers the way they are written.
 *
 * Deliberately forgiving about separators and strict about values: a scorer
 * mistyping "1 4 5 5 8" should be told there are two 5s, not silently given
 * ten players.
 */
object JerseyList {

    const val MIN = 1
    const val MAX = 99

    data class Result(
        val numbers: List<Int> = emptyList(),
        val problem: String? = null,
    ) {
        val isUsable: Boolean get() = problem == null && numbers.isNotEmpty()
    }

    /**
     * @param slots how many lineup slots are available; extra numbers are a
     *   problem rather than silently truncated.
     */
    fun parse(input: String, slots: Int): Result {
        // Anything that is not a digit separates: spaces, commas, newlines, a
        // "#" prefix, the trailing comma left by a rushed edit.
        val tokens = input.split(Regex("[^0-9]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return Result()

        val numbers = tokens.map { it.toIntOrNull() ?: return Result(problem = "Couldn't read \"$it\"") }

        numbers.firstOrNull { it < MIN || it > MAX }?.let {
            return Result(numbers, "$it isn't a shirt number ($MIN–$MAX)")
        }
        numbers.groupBy { it }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            return Result(numbers, "$it appears twice")
        }
        if (numbers.size > slots) {
            return Result(numbers, "${numbers.size} numbers for $slots slots")
        }
        return Result(numbers)
    }

    /** Numbers still needed to fill every slot; 0 when complete. */
    fun remaining(result: Result, slots: Int): Int = (slots - result.numbers.size).coerceAtLeast(0)
}
