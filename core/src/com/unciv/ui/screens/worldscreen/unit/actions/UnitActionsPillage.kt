package com.unciv.ui.screens.worldscreen.unit.actions

import com.unciv.GUI
import com.unciv.logic.civilization.PlayerUnitEconomyOperations
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.UnitPillage
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.ui.popups.ConfirmPopup
import yairm210.purity.annotations.Readonly

object UnitActionsPillage {

    internal fun getPillageActions(unit: MapUnit, tile: Tile): Sequence<UnitAction> {
        val pillageAction = getPillageAction(unit, tile) ?: return emptySequence()
        if (pillageAction.action == null || unit.civ.isAIOrAutoPlaying())
            return sequenceOf(pillageAction)
        return sequenceOf(UnitAction(UnitActionType.Pillage, 65f, pillageAction.title) {
            ConfirmPopup(
                GUI.getWorldScreen(),
                UnitPillage.confirmation(tile.getImprovementToPillageName()!!),
                "Pillage", true
            ) {
                pillageAction.action.invoke()
                GUI.setUpdateWorldOnNextRender()
            }.open()
        })
    }

    internal fun getPillageAction(unit: MapUnit, tile: Tile): UnitAction? {
        val improvementName = tile.getImprovementToPillageName()
        if (unit.isCivilian() || improvementName == null || tile.getOwner() == unit.civ) return null
        val operations = PlayerUnitEconomyOperations(unit.civ)
        val ordinaryPlayerUnit = unit.civ.isHuman() && unit.baseUnit.isLandUnit
        val available = if (ordinaryPlayerUnit) operations.pillage(unit)?.available == true
            else unit.hasMovement() && canPillage(unit, tile)
        return UnitAction(
            UnitActionType.Pillage, 65f, title = "${UnitActionType.Pillage} [$improvementName]",
            action = {
                // A confirmation must not execute against another improvement or another tile.
                if (unit.currentTile === tile && tile.getImprovementToPillageName() == improvementName) {
                    if (ordinaryPlayerUnit) operations.tryPillage(unit)
                    else UnitPillage.tryPillage(unit, tile)
                }
                Unit
            }.takeIf { available }
        )
    }

    // Public - used in UnitAutomation to inspect prospective tiles.
    @Readonly
    fun canPillage(unit: MapUnit, tile: Tile): Boolean = UnitPillage.canPillage(unit, tile)
}
