package com.unciv.logic.civilization

import com.unciv.Constants
import com.unciv.logic.battle.BattleUnitCapture
import com.unciv.logic.city.City
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueType

enum class CaptureChoice { Annex, Puppet, Raze, Liberate, Destroy, StopRazing, ReturnCivilian, KeepCivilian }

/** The displayed explanation remains available for disabled choices. */
data class CaptureOption(
    val choice: CaptureChoice,
    val label: String,
    val paragraphs: List<String>,
    val unavailableReasons: List<String> = emptyList(),
)

data class CaptureDecision(
    val title: String,
    val paragraphs: List<String>,
    val options: List<CaptureOption>,
    val cityId: String? = null,
    val unitId: Int? = null,
)

/** Shared capture-popup and city-screen choices. All writes recheck their offered choice.
 *
 * A conquered city retains its old owner until the human selects a disposition. The
 * owned CityConquered alert authorizes that transfer, never the caller's city ID alone.
 */
class PlayerCaptureOperations(private val civ: Civilization, private val spectatorMode: Boolean = false) {
    private fun canRead(): Boolean = !spectatorMode && !civ.isSpectator() &&
        civ.gameInfo.civilizations.any { it === civ }

    private fun capturedCity(alert: PopupAlert): City? {
        if (!canRead() || alert.type != AlertType.CityConquered || civ.popupAlerts.none { it === alert }) return null
        return civ.gameInfo.civilizations.asSequence().flatMap { it.cities.asSequence() }
            .firstOrNull { it.id == alert.value && it.hasJustBeenConquered && it.civ !== civ }
    }

    private fun recapturedCivilian(alert: PopupAlert): MapUnit? {
        if (!canRead() || alert.type != AlertType.RecapturedCivilian || civ.popupAlerts.none { it === alert }) return null
        val position = runCatching { HexCoord.fromString(alert.value) }.getOrNull() ?: return null
        val tile = civ.gameInfo.tileMap.getIfTileExistsOrNull(position.x, position.y) ?: return null
        val unit = tile.civilianUnit ?: return null
        val originalOwner = unit.originalOwningCiv ?: return null
        if (unit.civ !== civ || originalOwner === civ || originalOwner.isDefeated() ||
            !civ.knows(originalOwner) || civ.isAtWarWith(originalOwner)) return null
        return unit
    }

    /** Reads only an existing decision owned by this player; never dismisses stale alerts. */
    fun captureDecision(alert: PopupAlert): CaptureDecision? = synchronized(civ.gameInfo) {
        capturedCity(alert)?.let { city ->
            return@synchronized CaptureDecision(
                title = "What would you like to do with the city of [${city.name}]?",
                paragraphs = emptyList(),
                options = conqueredCityChoices(city),
                cityId = city.id,
            )
        }
        val unit = recapturedCivilian(alert) ?: return@synchronized null
        val owner = unit.originalOwningCiv!!
        CaptureDecision(
            title = "Return [${unit.name}] to [${owner.civName}]?",
            paragraphs = listOf("The [${unit.name}] we liberated originally belonged to [${owner.civName}]. They will be grateful if we return it to them."),
            options = listOf(
                CaptureOption(CaptureChoice.ReturnCivilian, Constants.yes, emptyList()),
                CaptureOption(CaptureChoice.KeepCivilian, Constants.no, emptyList()),
            ),
            unitId = unit.id,
        )
    }

    private fun conqueredCityChoices(city: City): List<CaptureOption> = buildList {
        if (city.foundingCivObject != null && city.civ !== city.foundingCivObject && civ !== city.foundingCivObject) {
            add(CaptureOption(CaptureChoice.Liberate, "Liberate (city returns to [${city.foundingCivObject!!.civName}])",
                listOf("Liberating a city returns it to its original owner, giving you a massive relationship boost with them!")))
        }
        if (civ.isOneCityChallenger()) {
            add(CaptureOption(CaptureChoice.Destroy, "Destroy",
                listOf("Destroying the city instantly razes the city to the ground.")))
            return@buildList
        }
        val mayAnnex = !civ.hasUnique(UniqueType.MayNotAnnexCities)
        val annexReason = if (mayAnnex) emptyList() else listOf("Your civilization may not annex this city.")
        add(CaptureOption(CaptureChoice.Annex, "Annex", if (mayAnnex) listOf(
            "Annexed cities become part of your regular empire.",
            "Their citizens generate 2x the unhappiness, unless you build a courthouse.",
        ) else annexReason, annexReason))
        add(CaptureOption(CaptureChoice.Puppet, "Puppet", buildList {
            add("Puppeted cities do not increase your tech or policy cost.")
            add("You have no control over the the production of puppeted cities.")
            add("Puppeted cities also generate 25% less Science and Culture.")
            if (mayAnnex) add("A puppeted city can be annexed at any time.")
        }))
        val razeReason = razeReasons(city, justCaptured = true)
        add(CaptureOption(CaptureChoice.Raze, "Raze", if (razeReason.isEmpty()) listOf(
            if (mayAnnex) "Razing the city annexes it, and starts burning the city to the ground."
            else "Razing the city puppets it, and starts burning the city to the ground.",
            "The population will gradually dwindle until the city is destroyed.",
        ) else razeReason, razeReason))
    }

    fun tryResolve(alert: PopupAlert, choice: CaptureChoice): Boolean = synchronized(civ.gameInfo) {
        if (!PlayerOperations(civ, spectatorMode).canAct()) return@synchronized false
        val decision = captureDecision(alert) ?: return@synchronized false
        if (decision.options.none { it.choice == choice && it.unavailableReasons.isEmpty() }) return@synchronized false
        val city = capturedCity(alert)
        if (city != null) {
            when (choice) {
                CaptureChoice.Liberate -> city.liberateCity(civ)
                CaptureChoice.Puppet -> city.puppetCity(civ)
                CaptureChoice.Annex -> { city.puppetCity(civ); city.annexCity() }
                CaptureChoice.Raze -> {
                    city.puppetCity(civ)
                    if (!civ.hasUnique(UniqueType.MayNotAnnexCities)) city.annexCity()
                    city.isBeingRazed = true
                }
                CaptureChoice.Destroy -> { city.puppetCity(civ); city.destroyCity(overrideSafeties = true) }
                else -> return@synchronized false
            }
        } else {
            val unit = recapturedCivilian(alert) ?: return@synchronized false
            when (choice) {
                CaptureChoice.ReturnCivilian -> returnCivilian(unit)
                CaptureChoice.KeepCivilian -> BattleUnitCapture.captureOrConvertToWorker(unit, civ)
                else -> return@synchronized false
            }
        }
        civ.popupAlerts.remove(alert)
        civ.gameInfo.checkForVictory()
        true
    }

    private fun returnCivilian(unit: MapUnit) {
        val owner = unit.originalOwningCiv!!
        val tile = unit.currentTile
        val unitName = unit.baseUnit.name
        unit.destroy()
        val closestCity = owner.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
        if (closestCity != null) owner.units.placeUnitNearTile(closestCity.location.toHexCoord(), unitName)
        if (owner.isCityState) owner.getDiplomacyManagerOrMeet(civ).addInfluence(45f)
        else if (owner.isMajorCiv()) owner.getDiplomacyManagerOrMeet(civ)
            .setModifier(DiplomaticModifiers.ReturnedCapturedUnits, 20f)
        val actions = sequence {
            yield(LocationAction(tile.position))
            if (closestCity != null) yield(LocationAction(closestCity.location))
            yield(DiplomacyAction(civ))
            yield(CivilopediaAction("Tutorial/Barbarians"))
        }
        owner.addNotification("Your captured [${unitName}] has been returned by [${civ.civName}]", actions,
            NotificationCategory.Diplomacy, NotificationIcon.Trade, unitName, civ.civName)
    }

    /** Exactly the annex/raze/stop controls available on an owned city screen. */
    fun cityChoices(city: City): List<CaptureOption> = synchronized(civ.gameInfo) {
        if (!canRead() || city.civ !== civ || civ.cities.none { it === city }) return@synchronized emptyList()
        val mayAnnex = !civ.hasUnique(UniqueType.MayNotAnnexCities)
        when {
            city.isPuppet && mayAnnex -> listOf(CaptureOption(CaptureChoice.Annex, "Annex city", emptyList()))
            city.isBeingRazed -> listOf(CaptureOption(CaptureChoice.StopRazing, "Stop razing city", emptyList()))
            else -> listOf(CaptureOption(CaptureChoice.Raze, "Raze city", emptyList(), buildList {
                if (!mayAnnex) add("Your civilization may not annex this city.")
                addAll(razeReasons(city, justCaptured = false))
            }))
        }
    }

    private fun razeReasons(city: City, justCaptured: Boolean): List<String> {
        if (city.canBeDestroyed(justCaptured)) return emptyList()
        if (civ.gameInfo.gameParameters.noCityRazing) return listOf("City razing is disabled for this game.")
        return listOf("Original capitals and holy cities cannot be razed.")
    }

    fun tryManageCity(city: City, choice: CaptureChoice): Boolean = synchronized(civ.gameInfo) {
        if (!PlayerOperations(civ, spectatorMode).canAct() ||
            cityChoices(city).none { it.choice == choice && it.unavailableReasons.isEmpty() }) return@synchronized false
        when (choice) {
            CaptureChoice.Annex -> city.annexCity()
            CaptureChoice.Raze -> city.isBeingRazed = true
            CaptureChoice.StopRazing -> city.isBeingRazed = false
            else -> return@synchronized false
        }
        true
    }
}
