package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.CaptureChoice
import com.unciv.logic.civilization.PlayerCaptureOperations
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PlayerOperations
import com.unciv.logic.civilization.PlayerDiplomaticCommunicationOperations
import com.unciv.logic.civilization.PlayerFriendshipOperations
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.*
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.fillPlaceholders
import com.unciv.models.translations.tr
import com.unciv.ui.audio.MusicMood
import com.unciv.ui.audio.MusicTrackChooserFlags
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.pad
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.diplomacyscreen.LeaderIntroTable
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import yairm210.purity.annotations.Readonly
import java.util.EnumSet
import kotlin.text.ifEmpty

/**
 * [Popup] communicating events other than trade offers to the player.
 * (e.g. First Contact, Wonder built, Tech researched,...)
 *
 * **Opens itself at the end of instantiation!**
 *
 * (In rare cases, it chooses not to: Mods making a RecapturedCivilian not find the unit as it was illegal and removed after the actual capture)
 *
 * Called in [WorldScreen].update, which pulls them from viewingCiv.popupAlerts.
 *
 * @param worldScreen The parent screen
 * @param popupAlert The [PopupAlert] entry to present
 *
 * @see AlertType
 *
 * Attention developers: This is a Popup with `Scrollability.WithoutButtons`, and that means the
 * content area has two parts - one scrolls and the bottom not. Use Popup's normal `add` for stuff that
 * should go to the upper scrolling part and all typical closing buttons should *only* use Popup's
 * add*Button methods - for a good exception see `addCityConquered`.
 * That also means colspan is independent for top and bottom, and you need no row() between them.
 */
class AlertPopup(
    private val worldScreen: WorldScreen,
    private val popupAlert: PopupAlert
): Popup(worldScreen) {
    
    companion object {
        private const val SEPARATOR_LINE_TO_TEXT_PADDING = 25f
        private val LIGHTER_RED_COLOR = Color(1f, 1/3f, 1/3f, 1f)
        private val LIGHTER_GREEN_COLOR = Color(1/3f, 1f, 1/3f, 1f)
        private val LIGHTER_ORANGE_COLOR = Color(1f, 2/5f, 0f, 1f)
    }

    //region convenience getters
    private val music get() = UncivGame.Current.musicController
    private val gameInfo get() = worldScreen.gameInfo
    private val viewingCiv get() = worldScreen.selectedGameView.civView.getCiv()
    private val stageWidth get() = worldScreen.stage.width
    private val stageHeight get() = worldScreen.stage.height
    @Readonly private fun getCiv(civName: String) = gameInfo.getCivilization(civName)
    @Readonly private fun getCity(cityId: String) = gameInfo.getCities().first { it.id == cityId }
    //endregion

    // This redirects all addCloseButton uses with only text and no action to accept the space key
    private fun addCloseButton(text: String = Constants.close) =
        addCloseButton(text, KeyboardBinding.NextTurnAlternate, null)

    init {
        var shouldOpen = true

        // This makes the buttons fill up available width. See comments in #9559.
        // To implement a middle ground, I would either simply replace growX() with minWidth(240f) or so,
        // or replace the Popup.equalizeLastTwoButtonWidths() function with something intelligent not
        // limited to two buttons.
        bottomTable.defaults().growX()

        when (popupAlert.type) {
            // Cities
            AlertType.CityConquered -> addCityConquered()
            AlertType.CityTraded -> addCityTraded()
            AlertType.DiplomaticMarriage -> addDiplomaticMarriage()
            // Demands and diplomacy
            AlertType.FirstContact -> shouldOpen = addFirstContact()
            AlertType.WarDeclaration -> shouldOpen = addWarDeclaration()
            AlertType.BorderConflict -> shouldOpen = addBorderConflict()
            AlertType.TilesStolen -> shouldOpen = addTilesStolen()
            AlertType.Denounced -> shouldOpen = addDenouncement()
            
            // demands
            AlertType.DemandToStopSettlingCitiesNear -> shouldOpen = addDemand(Demand.DoNotSettleNearUs)
            AlertType.CitySettledNearOtherCivDespiteOurPromise -> shouldOpen = addDemandViolationNoticed(Demand.DoNotSettleNearUs)
            AlertType.DemandToStopSpreadingReligion -> shouldOpen = addDemand(Demand.DoNotSpreadReligion)
            AlertType.ReligionSpreadDespiteOurPromise -> shouldOpen = addDemandViolationNoticed(Demand.DoNotSpreadReligion)
            AlertType.DemandToStopSpyingOnUs -> shouldOpen = addDemand(Demand.DontSpyOnUs)
            AlertType.SpyingOnUsDespiteOurPromise -> shouldOpen = addDemand(Demand.DontSpyOnUs)
            AlertType.DemandToNotAttackUs -> shouldOpen = addDemand(Demand.DoNotAttackUs)
            AlertType.AttackedUsDespitePromise -> shouldOpen = addDemandViolationNoticed(Demand.DoNotAttackUs)
            AlertType.AcceptingDemand -> shouldOpen = addAcceptingDemand()
            AlertType.RejectingDemand -> shouldOpen = addRejectingDemand()
            
            AlertType.DeclarationOfFriendship -> shouldOpen = addDeclarationOfFriendship()
            AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor, AlertType.AttackedAllyMinor -> 
                shouldOpen = addBulliedOrAttackedProtectedOrAlliedMinor()
            AlertType.Defeated -> addDefeated()
            // We did stuff
            AlertType.WonderBuilt -> shouldOpen = addWonderBuilt()
            AlertType.TechResearched -> shouldOpen = addTechResearched()
            AlertType.GoldenAge -> shouldOpen = addGoldenAge()
            AlertType.StartIntro -> shouldOpen = addStartIntro()
            AlertType.RecapturedCivilian -> shouldOpen = addRecapturedCivilian()
            AlertType.GameHasBeenWon -> addGameHasBeenWon()
            AlertType.Event -> shouldOpen = addEvent()
        }
        if (shouldOpen) open()
        else viewingCiv.popupAlerts.remove(popupAlert)
    }

    //region AlertType handlers

    private fun addBorderConflict(): Boolean = addDiplomaticDecision()

    private fun addTilesStolen(): Boolean = addDiplomaticDecision()

    private fun addDiplomaticDecision(): Boolean {
        val operations = PlayerDiplomaticCommunicationOperations(viewingCiv)
        val decision = operations.decision(popupAlert) ?: return false
        val other = getCiv(decision.civilizationId)
        addLeaderName(other)
        decision.content.paragraphs.forEach { addGoodSizedLabel(it).row() }
        for (option in decision.options) {
            val button = addButton(option.text, if (option.name in setOf("Acknowledge", "WithdrawProtection", "Refuse")) KeyboardBinding.Cancel else KeyboardBinding.Confirm) {
                if (operations.tryRespond(popupAlert, option.name)) close()
            }
            if (!option.available) button.actor.disable()
            button.row()
        }
        decision.voice?.let { music.playVoice("${other.nation.name}.$it") }
        return true
    }

    private fun addBulliedOrAttackedProtectedOrAlliedMinor(): Boolean = addDiplomaticDecision()

    private fun addCityConquered() {
        val operations = PlayerCaptureOperations(viewingCiv)
        val decision = operations.captureDecision(popupAlert) ?: return
        addGoodSizedLabel(decision.title, Constants.headingFontSize, hideIcons = true).padBottom(20f).row()
        for ((index, option) in decision.options.withIndex()) {
            if (index != 0) addSeparator()
            val button = option.label.toTextButton()
            if (option.unavailableReasons.isNotEmpty()) button.disable()
            else {
                button.onActivation {
                    if (operations.tryResolve(popupAlert, option.choice)) close()
                }
                button.keyShortcuts.add(option.choice.name.first().lowercaseChar())
            }
            add(button).row()
            option.paragraphs.forEach { addGoodSizedLabel(it).row() }
        }
    }

    private fun addDemandViolationNoticed(demand: Demand): Boolean = addDiplomaticDecision()

    private fun addCityTraded() {
        addCityConquered()
    }

    private fun addDeclarationOfFriendship(): Boolean {
        val operations = PlayerFriendshipOperations(viewingCiv)
        val decision = operations.decision(popupAlert) ?: return false
        val otherciv = getCiv(popupAlert.value)
        addLeaderName(otherciv)
        addTopicHeader("DECLARATION OF FRIENDSHIP", LIGHTER_GREEN_COLOR)
        addGoodSizedLabel(decision.content.paragraphs.last()).row()
        val accept = addButton(decision.content.acknowledgement, KeyboardBinding.Confirm) {
            if (operations.tryRespond(popupAlert, true)) close()
        }
        if (decision.unavailableReason != null) {
            accept.actor.disable()
            addGoodSizedLabel(decision.unavailableReason).row()
        }
        accept.row()
        addButton(decision.content.additionalAcknowledgements.single(), KeyboardBinding.Cancel) {
            if (operations.tryRespond(popupAlert, false)) close()
        }.row()
        val music = UncivGame.Current.musicController
        music.playVoice("${otherciv.nation.name}.declaringFriendship")
        return true
    }

    private fun addDenouncement(): Boolean = addDiplomaticDecision()

    private fun addDefeated() {
        val content = PlayerOperations(viewingCiv).informationalPopupContent(popupAlert) ?: return
        val civInfo = getCiv(popupAlert.value)
        addLeaderName(civInfo)
        content.paragraphs.forEach { addGoodSizedLabel(it).row() }
        addCloseButton(content.acknowledgement)
        music.chooseTrack(civInfo.civName, MusicMood.Defeat, EnumSet.of(MusicTrackChooserFlags.SuffixMustMatch))
        music.playVoice("${civInfo.civName}.defeated")
    }

    private fun addDemand(demand: Demand): Boolean = addDiplomaticDecision()

    private fun addAcceptingDemand(): Boolean = addDiplomaticDecision()

    private fun addRejectingDemand(): Boolean = addDiplomaticDecision()

    private fun addDiplomaticMarriage() {
        val city = getCity(popupAlert.value)
        addGoodSizedLabel(city.name.tr() + ": " + "What would you like to do with the city?".tr(), Constants.headingFontSize) // Add name because there might be several cities
            .padBottom(20f).row()
        val marryingCiv = gameInfo.getCurrentPlayerCivilization()

        if (marryingCiv.isOneCityChallenger()) {
            addDestroyOption {
                city.destroyCity(overrideSafeties = true)
            }
        } else {
            val mayAnnex = !marryingCiv.hasUnique(UniqueType.MayNotAnnexCities)
            addAnnexOption(city, mayAnnex) {}
            addSeparator()

            addPuppetOption(mayAnnex) {
                city.isPuppet = true
                city.cityStats.update()
            }
        }
    }

    private fun addFirstContact(): Boolean {
        val content = PlayerOperations(viewingCiv).informationalPopupContent(popupAlert) ?: return false
        val civInfo = getCiv(popupAlert.value)
        addLeaderName(civInfo)
        music.chooseTrack(civInfo.civName, MusicMood.themeOrPeace, MusicTrackChooserFlags.setSpecific)
        music.playVoice("${civInfo.civName}.introduction")
        content.paragraphs.forEach { addGoodSizedLabel(it).row() }
        addCloseButton(content.acknowledgement)
        return true
    }

    private fun addGameHasBeenWon() {
        val content = PlayerOperations(viewingCiv).informationalPopupContent(popupAlert) ?: return
        addGoodSizedLabel(content.title).row()
        addButton("Victory status") { close(); worldScreen.game.pushScreen{ VictoryScreen(worldScreen) } }.row()
        addCloseButton(content.acknowledgement)
    }

    private fun addGoldenAge(): Boolean {
        val content = PlayerOperations(viewingCiv).informationalPopupContent(popupAlert) ?: return false
        addGoodSizedLabel(content.title)
        addSeparator().padBottom(SEPARATOR_LINE_TO_TEXT_PADDING)
        content.paragraphs.forEach { addGoodSizedLabel(it).row() }
        addCloseButton(content.acknowledgement)
        music.chooseTrack(viewingCiv.civName, MusicMood.Golden, MusicTrackChooserFlags.setSpecific)
        return true
    }

    /** @return false to skip opening this Popup, as we're running in the initialization phase before the Popup is open */
    private fun addRecapturedCivilian(): Boolean {
        val operations = PlayerCaptureOperations(viewingCiv)
        val decision = operations.captureDecision(popupAlert) ?: return false
        addGoodSizedLabel(decision.title)
        addSeparator().padBottom(SEPARATOR_LINE_TO_TEXT_PADDING)
        decision.paragraphs.forEach { addGoodSizedLabel(it).row() }
        bottomTable.defaults().pad(0f, 30f)
        for (option in decision.options) {
            val binding = if (option.choice == CaptureChoice.ReturnCivilian) KeyboardBinding.Confirm else KeyboardBinding.Cancel
            addButton(option.label, binding) {
                if (operations.tryResolve(popupAlert, option.choice)) close()
            }
        }
        return true
    }

    private fun addStartIntro(): Boolean {
        val content = PlayerOperations(viewingCiv).informationalPopupContent(popupAlert) ?: return false
        val civInfo = viewingCiv
        addLeaderName(civInfo)
        content.paragraphs.forEach { addGoodSizedLabel(it).row() }
        addCloseButton(content.acknowledgement)

        // Since there's introduction text, play the startIntroPart1 voice hook with the nation's theme.
        val music = UncivGame.Current.musicController
        music.chooseTrack(civInfo.nation.name, MusicMood.themeOrPeace, MusicTrackChooserFlags.setSpecific)
        music.playVoice("${civInfo.nation.name}.startIntroPart1")
        return true
    }

    private fun addTechResearched(): Boolean {
        val content = PlayerOperations(viewingCiv).informationalPopupContent(popupAlert) ?: return false
        addGoodSizedLabel(content.title)
        addSeparator().padBottom(SEPARATOR_LINE_TO_TEXT_PADDING)
        val centerTable = Table()
        centerTable.add(content.quote.orEmpty().toLabel().apply { wrap = true }).width(stageWidth / 3)
        centerTable.add(ImageGetter.getTechIconPortrait(popupAlert.value, 100f)).pad(20f)
        val descriptionScroll = ScrollPane(content.paragraphs.joinToString("\n\n").toLabel().apply { wrap = true })
        centerTable.add(descriptionScroll).width(stageWidth / 3).maxHeight(stageHeight / 2)
        add(centerTable).row()
        addCloseButton(content.acknowledgement)
        music.chooseTrack(popupAlert.value, MusicMood.Researched, MusicTrackChooserFlags.setSpecific)
        return true
    }

    private fun addWarDeclaration(): Boolean {
        val content = PlayerOperations(viewingCiv).informationalPopupContent(popupAlert) ?: return false
        val civInfo = getCiv(popupAlert.value)
        addLeaderName(civInfo)
        addTopicHeader(content.paragraphs.first(), LIGHTER_RED_COLOR)
        content.paragraphs.drop(1).forEach { addGoodSizedLabel(it).row() }
        content.additionalAcknowledgements.forEach { addCloseButton(it) }
        addCloseButton(content.acknowledgement)
        equalizeLastTwoButtonWidths()
        music.chooseTrack(civInfo.civName, MusicMood.War, MusicTrackChooserFlags.setSpecific)
        music.playVoice("${civInfo.civName}.declaringWar")
        return true
    }

    private fun addTopicHeader(text: String, color: Color) {
        addGoodSizedLabel(text, color=color, size=Constants.smallerHeadingFontSize)
            .padBottom(20f).row()
    }

    private fun addWonderBuilt(): Boolean {
        val content = PlayerOperations(viewingCiv).informationalPopupContent(popupAlert) ?: return false
        val wonderName = popupAlert.value
        addGoodSizedLabel(content.title)
        addSeparator().padBottom(10f)
        if(ImageGetter.wonderImageExists(wonderName)) {    // Wonder Graphic exists
            if(stageHeight * 3 > stageWidth * 4) {    // Portrait
                add(ImageGetter.getWonderImage(wonderName))
                    .width(stageWidth / 1.5f)
                    .height(stageWidth / 3)
                    .row()
            }
            else {  // Landscape (or squareish)
                add(ImageGetter.getWonderImage(wonderName))
                    .width(stageWidth / 2.5f)
                    .height(stageWidth / 5)
                    .row()
            }
        } else {    // Fallback
            add(ImageGetter.getConstructionPortrait(wonderName, 100f)).pad(20f).row()
        }

        val centerTable = Table()
        val centerTableColumnWidth = stageWidth / if (content.quote == null) 2 else 3
        if (content.quote != null) {
            centerTable.add(content.quote.toLabel().apply { wrap = true })
                .width(centerTableColumnWidth)
                .pad(10f)
        }
        centerTable.add(content.paragraphs.joinToString("\n\n").toLabel().apply { wrap = true })
            .width(centerTableColumnWidth)
            .pad(10f)
        add(centerTable).row()
        addCloseButton(content.acknowledgement)
        music.chooseTrack(wonderName, MusicMood.Wonder, MusicTrackChooserFlags.setSpecific)
        return true
    }

    //endregion
    //region Helpers

    private fun addLeaderName(civInfo: Civilization) {
        add(LeaderIntroTable(civInfo))
        addSeparator().padBottom(SEPARATOR_LINE_TO_TEXT_PADDING)
    }

    private fun addDestroyOption(destroyAction: () -> Unit) {
        val button = "Destroy".toTextButton()
        button.onActivation {
            destroyAction()
            close()
        }
        button.keyShortcuts.add('d')
        add(button).row()
        addGoodSizedLabel("Destroying the city instantly razes the city to the ground.").row()
    }

    private fun addAnnexOption(city: City, mayAnnex: Boolean, annexAction: () -> Unit) {
        val button = "Annex".toTextButton()
        button.apply {
            if (!mayAnnex) disable() else {
                button.onActivation {
                    annexAction()
                    city.annexCity()
                    close()
                }
                button.keyShortcuts.add('a')
            }
        }
        add(button).row()
        if (mayAnnex) {
            addGoodSizedLabel("Annexed cities become part of your regular empire.").row()
            addGoodSizedLabel("Their citizens generate 2x the unhappiness, unless you build a courthouse.").row()
        } else {
            addGoodSizedLabel("Your civilization may not annex this city.").row()
        }

    }

    private fun addPuppetOption(mayAnnex: Boolean, puppetAction: () -> Unit) {
        val button = "Puppet".toTextButton()
        button.onActivation {
            puppetAction()
            close()
        }
        button.keyShortcuts.add('p')
        add(button).row()
        addGoodSizedLabel("Puppeted cities do not increase your tech or policy cost.").row()
        addGoodSizedLabel("You have no control over the the production of puppeted cities.").row()
        addGoodSizedLabel("Puppeted cities also generate 25% less Science and Culture.").row()
        if (mayAnnex) addGoodSizedLabel("A puppeted city can be annexed at any time.").row()
    }

    /** Returns if event was triggered correctly */
    private fun addEvent(): Boolean {
        // The event string is in the format "eventName" + (Constants.stringSplitCharacter + "unitId=1234")?
        // We explicitly specify that this is a unitId, to enable us to add other context info in the future - for example city id
        val splitString = popupAlert.value.split(Constants.stringSplitCharacter)
        val eventName = splitString[0]
        var unit: MapUnit? = null
        for (i in 1 until splitString.size) {
            if (splitString[i].startsWith("unitId=")){
                val unitId = splitString[i].substringAfter("unitId=").toInt()
                unit = viewingCiv.units.getUnitById(unitId)
            }
        }
        
        
        val event = gameInfo.ruleset.events[eventName] ?: return false
        val render = RenderEvent(event, worldScreen, unit) { close() }
        if (!render.isValid) return false
        add(render).pad(0f).row()
        return true
    }

    //endregion

    override fun close() {
        if (popupAlert.type in PlayerOperations.informationalAlerts) {
            PlayerOperations(viewingCiv).tryAcknowledgeAlert(popupAlert)
        } else viewingCiv.popupAlerts.remove(popupAlert)
        worldScreen.shouldUpdate = true
        super.close()
    }
}
