package com.unciv.logic.map.tile

import com.unciv.logic.city.City

enum class SettlementRejection { WATER, IMPASSABLE, CITY_TOO_CLOSE, FOREIGN_TERRITORY }

/** Native site restrictions. Callers must filter city information for their viewer. */
data class SettlementBlocker(
    val reason: SettlementRejection,
    val city: City? = null,
    val distance: Int? = null,
    val minimumDistance: Int? = null,
)
