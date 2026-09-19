package com.unciv.logic.civilization

import com.unciv.logic.IsPartOfGameInfoSerialization

enum class AlertType : IsPartOfGameInfoSerialization {
    Defeated,
    WonderBuilt,
    TechResearched,
    WarDeclaration,
    FirstContact,
    CityConquered,
    CityTraded,
    BorderConflict,
    TilesStolen,

    DemandToStopSettlingCitiesNear,
    CitySettledNearOtherCivDespiteOurPromise,

    DemandToStopSpreadingReligion,
    ReligionSpreadDespiteOurPromise,
    
    DemandToStopSpyingOnUs,
    SpyingOnUsDespiteOurPromise,
    
    DemandToNotAttackUs,
    AttackedUsDespitePromise,
    
    AcceptingDemand,
    RejectingDemand,

    GoldenAge,
    DeclarationOfFriendship,
    StartIntro,
    DiplomaticMarriage,
    BulliedProtectedMinor,
    AttackedProtectedMinor,
    AttackedAllyMinor,
    RecapturedCivilian,
    GameHasBeenWon,
    Event,
    
    Denounced
}

class PopupAlert : IsPartOfGameInfoSerialization {
    lateinit var type: AlertType
    lateinit var value: String
    var requestId: String = ""

    /** Optional observer for the result of a native friendship request. */
    @Transient
    var onResponse: ((Boolean) -> Unit)? = null

    fun clone() = PopupAlert(type, value).also { it.requestId = requestId }

    constructor(type: AlertType, value: String) {
        this.type = type
        this.value = value
    }

    constructor() // for json serialization
}
